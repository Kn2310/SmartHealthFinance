package com.smarthealthfinance.cards.application.usecase;

import com.smarthealthfinance.cards.application.CardsTestContext;
import com.smarthealthfinance.cards.application.dto.CardView;
import com.smarthealthfinance.cards.application.dto.CardsSummaryView;
import com.smarthealthfinance.cards.application.dto.FutureInvoicesView;
import com.smarthealthfinance.cards.application.dto.InvoiceSummaryView;
import com.smarthealthfinance.cards.application.dto.InvoiceView;
import com.smarthealthfinance.cards.application.dto.UtilizationView;
import com.smarthealthfinance.cards.application.exception.InvoiceNotFoundException;
import com.smarthealthfinance.cards.application.port.CardLedgerReader.CommittedInstallment;
import com.smarthealthfinance.cards.application.service.CardEvents;
import com.smarthealthfinance.cards.domain.enums.InvoiceStatus;
import com.smarthealthfinance.cards.domain.model.CardInvoice;
import com.smarthealthfinance.cards.domain.model.CreditCard;
import com.smarthealthfinance.shared.domain.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

import static com.smarthealthfinance.cards.application.CardsTestContext.BRL;
import static com.smarthealthfinance.shared.domain.InvalidValueAssert.assertInvalidValue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Cartões de 09-dados-de-exemplo, com hoje = 22 set 2026. */
class CardReadingsTest {

    private final CardsTestContext ctx = new CardsTestContext();
    private CardView aurora;
    private CardView horizonte;

    @BeforeEach
    void sampleData() {
        aurora = ctx.createCard.execute(ctx.anasWorkspaceId(), ctx.auroraCommand());
        horizonte = ctx.createCard.execute(ctx.anasWorkspaceId(), new CreateCard.Command("Horizonte Gold",
                "Conta Horizonte", "VISA", "0937", "6000.00", "BRL", 5, 12, null));

        CreditCard card = ctx.storedCard(aurora.id());
        ctx.invoiceWith(card, YearMonth.of(2026, 6), "2710.40");
        ctx.invoiceWith(card, YearMonth.of(2026, 7), "2954.10");
        ctx.invoiceWith(card, YearMonth.of(2026, 8), "3012.10");
        ctx.invoiceWith(card, YearMonth.of(2026, 9), "3412.80");
        for (int number = 4; number <= 10; number++) {
            CardInvoice future = ctx.invoiceWith(card, YearMonth.of(2026, 9).plusMonths(number - 3L), "480.00");
            ctx.ledger.commit(card.id(), new CommittedInstallment(future.id(), NOTEBOOK, "Notebook", number, 10,
                    brl("480.00")));
        }
        ctx.ledger.pay(card.workspaceId(), card.id(), brl("8676.60"));

        ctx.invoiceWith(ctx.storedCard(horizonte.id()), YearMonth.of(2026, 10), "1020.40");
    }

    private static final UUID NOTEBOOK = UUID.randomUUID();

    @Test
    void summaryMatchesTheCardsScreen() {
        CardsSummaryView summary = ctx.summary.execute(ctx.anasWorkspaceId());

        // D-Cards: limite total 18.000, utilizado 7.793,20 (43%), faturas abertas 4.433,20, próximo vencimento 05 out.
        assertThat(summary.totalLimit()).isEqualTo(brl("18000.00"));
        assertThat(summary.utilized()).isEqualTo(brl("7793.20"));
        assertThat(summary.openInvoices()).isEqualTo(brl("4433.20"));
        assertThat(summary.nextDueDate()).isEqualTo(LocalDate.parse("2026-10-05"));
        assertThat(summary.asOf()).isEqualTo(LocalDate.parse("2026-09-22"));
        assertThat(summary.calculationVersion()).isEqualTo("invoice-v1");

        CardsSummaryView.CardSummary auroraSummary = summary.cards().getFirst();
        assertThat(auroraSummary.currentInvoice().amount()).isEqualTo(brl("3412.80"));
        assertThat(auroraSummary.currentInvoice().status()).isEqualTo(InvoiceStatus.OPEN);
        assertThat(auroraSummary.currentInvoice().partial()).isTrue();
        assertThat(auroraSummary.utilization().utilizationPercent()).isEqualTo(56);
        assertThat(auroraSummary.nextInvoiceCommitted()).isEqualTo(brl("480.00"));

        CardsSummaryView.CardSummary horizonteSummary = summary.cards().get(1);
        assertThat(horizonteSummary.currentInvoice().dueDate()).isEqualTo(LocalDate.parse("2026-10-12"));
        assertThat(horizonteSummary.utilization().utilized()).isEqualTo(brl("1020.40"));
        assertThat(horizonteSummary.utilization().utilizationPercent()).isEqualTo(17);
        assertThat(horizonteSummary.nextInvoiceCommitted()).isEqualTo(brl("0.00"));
    }

    @Test
    void utilizationOfAurora() {
        UtilizationView utilization = ctx.utilization.execute(ctx.anasWorkspaceId(), aurora.id());

        assertThat(utilization.utilized()).isEqualTo(brl("6772.80"));
        assertThat(utilization.available()).isEqualTo(brl("5227.20"));
        assertThat(utilization.utilizationPercent()).isEqualTo(56);
        assertThat(utilization.utilizationWithoutFuturePercent()).isEqualTo(28);
        assertThat(utilization.composition().currentInvoice()).isEqualTo(brl("3412.80"));
        assertThat(utilization.composition().futureInstallments()).isEqualTo(brl("3360.00"));
    }

    @Test
    void currentAndPastInvoices() {
        InvoiceView current = ctx.getInvoice.current(ctx.anasWorkspaceId(), aurora.id());
        assertThat(current.invoice().dueMonth()).isEqualTo(YearMonth.of(2026, 10));
        assertThat(current.invoice().closingDate()).isEqualTo(LocalDate.parse("2026-09-28"));
        assertThat(current.invoice().amount()).isEqualTo(brl("3412.80"));

        InvoiceView september = ctx.getInvoice.byDueMonth(ctx.anasWorkspaceId(), aurora.id(), "2026-09");
        assertThat(september.invoice().status()).isEqualTo(InvoiceStatus.PAID);
        assertThat(september.invoice().paid()).isEqualTo(brl("3012.10"));
        assertThat(september.invoice().partial()).isFalse();

        // Fatura passada sem lançamentos não existe; a futura sem lançamentos existe com valor zero.
        assertThatThrownBy(() -> ctx.getInvoice.byDueMonth(ctx.anasWorkspaceId(), aurora.id(), "2026-01"))
                .isInstanceOf(InvoiceNotFoundException.class);
        InvoiceView farFuture = ctx.getInvoice.byDueMonth(ctx.anasWorkspaceId(), aurora.id(), "2028-01");
        assertThat(farFuture.invoice().status()).isEqualTo(InvoiceStatus.FUTURE);
        assertThat(farFuture.invoice().amount()).isEqualTo(brl("0.00"));

        assertInvalidValue(() -> ctx.getInvoice.byDueMonth(ctx.anasWorkspaceId(), aurora.id(), "2026-13"),
                "dueMonth", "INVALID");
    }

    @Test
    void listsInvoicesOfTheRangeMostRecentFirst() {
        assertThat(ctx.listInvoices.execute(ctx.anasWorkspaceId(), aurora.id(), null, null))
                .extracting(InvoiceSummaryView::dueMonth)
                .containsExactly(YearMonth.of(2026, 10), YearMonth.of(2026, 9), YearMonth.of(2026, 8),
                        YearMonth.of(2026, 7));
        assertThat(ctx.listInvoices.execute(ctx.anasWorkspaceId(), aurora.id(), "2026-11", "2027-05")).hasSize(8);

        assertInvalidValue(() -> ctx.listInvoices.execute(ctx.anasWorkspaceId(), aurora.id(), "2026-10", "2026-01"),
                "from", "AFTER_TO");
        assertInvalidValue(() -> ctx.listInvoices.execute(ctx.anasWorkspaceId(), aurora.id(), "2024-01", "2026-01"),
                "to", "RANGE_TOO_LONG");
    }

    @Test
    void futureInvoicesShowOnlyCommittedInstallments() {
        FutureInvoicesView future = ctx.future.execute(ctx.anasWorkspaceId(), aurora.id());

        assertThat(future.months()).extracting(FutureInvoicesView.Month::dueMonth)
                .containsExactly(YearMonth.of(2026, 11), YearMonth.of(2026, 12), YearMonth.of(2027, 1),
                        YearMonth.of(2027, 2), YearMonth.of(2027, 3), YearMonth.of(2027, 4), YearMonth.of(2027, 5));
        assertThat(future.months().getFirst().installments()).singleElement()
                .satisfies(installment -> assertThat(installment.number()).isEqualTo(4));
        assertThat(future.totalCommitted()).isEqualTo(brl("3360.00"));
    }

    @Test
    void closedInvoicesAreNotifiedOnce() {
        ctx.outbox.clear();

        int first = ctx.notifyClosed.execute();
        int second = ctx.notifyClosed.execute();

        // Fechadas até 22 set: as faturas do Aurora com vencimento em jul, ago e set.
        assertThat(first).isEqualTo(3);
        assertThat(second).isZero();
        assertThat(ctx.outbox).allSatisfy(event -> {
            assertThat(event.eventType()).isEqualTo(CardEvents.INVOICE_CLOSED);
            assertThat(event.payload()).containsOnlyKeys("cardId", "invoiceId", "dueMonth");
        });
    }

    private static Money brl(String amount) {
        return Money.of(amount, BRL);
    }
}
