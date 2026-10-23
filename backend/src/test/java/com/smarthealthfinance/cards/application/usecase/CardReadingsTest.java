package com.smarthealthfinance.cards.application.usecase;

import com.smarthealthfinance.cards.application.CardsTestContext;
import com.smarthealthfinance.cards.application.dto.CardView;
import com.smarthealthfinance.cards.application.dto.CardsSummaryView;
import com.smarthealthfinance.cards.application.dto.FutureInvoicesView;
import com.smarthealthfinance.cards.application.dto.InstallmentPlanView;
import com.smarthealthfinance.cards.application.dto.InvoiceItemPageView;
import com.smarthealthfinance.cards.application.dto.InvoiceItemView;
import com.smarthealthfinance.cards.application.dto.InvoiceSummaryView;
import com.smarthealthfinance.cards.application.dto.InvoiceView;
import com.smarthealthfinance.cards.application.dto.UtilizationView;
import com.smarthealthfinance.cards.application.exception.InvoiceNotFoundException;
import com.smarthealthfinance.cards.application.service.CardEvents;
import com.smarthealthfinance.cards.domain.enums.InstallmentPlanStatus;
import com.smarthealthfinance.cards.domain.enums.InstallmentStatus;
import com.smarthealthfinance.cards.domain.enums.InvoiceItemKind;
import com.smarthealthfinance.cards.domain.enums.InvoiceStatus;
import com.smarthealthfinance.cards.domain.model.CreditCard;
import com.smarthealthfinance.cards.domain.service.InstallmentSplitter;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.application.exception.TransactionNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

import static com.smarthealthfinance.cards.application.CardsTestContext.BRL;
import static com.smarthealthfinance.shared.domain.InvalidValueAssert.assertInvalidValue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cartões de 09-dados-de-exemplo, com hoje = 22 set 2026. O notebook (10 × R$ 480, compra em 12 jul) é uma compra só:
 * parcelas 1 e 2 nas faturas pagas de agosto e setembro, 3 na de outubro (aberta) e 4–10 de novembro a maio.
 */
class CardReadingsTest {

    private static final UUID NOTEBOOK = UUID.randomUUID();

    private final CardsTestContext ctx = new CardsTestContext();
    private CardView aurora;
    private CardView horizonte;

    @BeforeEach
    void sampleData() {
        aurora = ctx.createCard.execute(ctx.anasWorkspaceId(), ctx.auroraCommand());
        horizonte = ctx.createCard.execute(ctx.anasWorkspaceId(), new CreateCard.Command("Horizonte Gold",
                "Conta Horizonte", "VISA", "0937", "6000.00", "BRL", 5, 12, null));

        CreditCard card = ctx.storedCard(aurora.id());
        ctx.purchase(card, NOTEBOOK, LocalDate.parse("2026-07-12"), "Notebook",
                InstallmentSplitter.split(brl("4800.00"), 10));
        // Faturas de jul (2.710,40), ago (2.954,10) e set (3.012,10) pagas; outubro aberta com 3.412,80.
        ctx.invoiceWith(card, YearMonth.of(2026, 6), "2710.40");
        ctx.invoiceWith(card, YearMonth.of(2026, 7), "2474.10");
        ctx.invoiceWith(card, YearMonth.of(2026, 8), "2532.10");
        ctx.invoiceWith(card, YearMonth.of(2026, 9), "2932.80");
        ctx.ledger.pay(card.workspaceId(), card.id(), LocalDate.parse("2026-09-05"), brl("8676.60"));
        // O histórico de junho em diante veio de faturas importadas (ADR-0025 §15).
        ctx.cards.recedeTrackingStart(card.workspaceId(), card.id(), YearMonth.of(2026, 6));

        ctx.invoiceWith(ctx.storedCard(horizonte.id()), YearMonth.of(2026, 10), "1020.40");
    }

    @Test
    void summaryMatchesTheCardsScreen() {
        CardsSummaryView summary = ctx.summary.execute(ctx.anasWorkspaceId());

        // D-Cards: limite total 18.000, utilizado 7.793,20 (43%), faturas abertas 4.433,20, próximo vencimento 05 out.
        assertThat(summary.totalLimit()).isEqualTo(brl("18000.00"));
        assertThat(summary.utilized()).isEqualTo(brl("7793.20"));
        assertThat(summary.openInvoices()).isEqualTo(brl("4433.20"));
        assertThat(summary.nextDueDate()).isEqualTo(LocalDate.parse("2026-10-05"));
        assertThat(summary.asOf()).isEqualTo(LocalDate.parse("2026-09-22"));
        assertThat(summary.calculationVersion()).isEqualTo("invoice-v2");

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
        assertThat(current.itemCount()).isEqualTo(2);

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
    void invoiceItemsShowTheInstallmentAndArePaged() {
        InvoiceItemPageView page = ctx.listItems.execute(ctx.anasWorkspaceId(), aurora.id(), "2026-10", 0, 1);

        assertThat(page.totalItems()).isEqualTo(2);
        assertThat(page.items()).hasSize(1);
        InvoiceItemPageView all = ctx.listItems.execute(ctx.anasWorkspaceId(), aurora.id(), "2026-10", null, null);
        assertThat(all.items()).filteredOn(item -> NOTEBOOK.equals(item.transactionId())).singleElement()
                .satisfies(item -> {
                    assertThat(item.kind()).isEqualTo(InvoiceItemKind.CHARGE);
                    assertThat(item.installmentNumber()).isEqualTo(3);
                    assertThat(item.installmentCount()).isEqualTo(10);
                    assertThat(item.amount()).isEqualTo(brl("480.00"));
                    assertThat(item.occurredOn()).isEqualTo(LocalDate.parse("2026-07-12"));
                });
        assertInvalidValue(() -> ctx.listItems.execute(ctx.anasWorkspaceId(), aurora.id(), "2026-10", 0, 101),
                "pageSize", "OUT_OF_RANGE");
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
    void notebookInstallmentPlan() {
        InstallmentPlanView plan = ctx.plans.get(ctx.anasWorkspaceId(), aurora.id(), NOTEBOOK);

        // D-Installment: 2 pagas (R$ 960), parcela 3 nesta fatura, restam 7 (R$ 3.360) até mai/2027.
        assertThat(plan.total()).isEqualTo(brl("4800.00"));
        assertThat(plan.count()).isEqualTo(10);
        assertThat(plan.installmentAmount()).isEqualTo(brl("480.00"));
        assertThat(plan.paidCount()).isEqualTo(2);
        assertThat(plan.paidAmount()).isEqualTo(brl("960.00"));
        assertThat(plan.currentNumber()).isEqualTo(3);
        assertThat(plan.remainingAfterCurrent()).isEqualTo(brl("3360.00"));
        assertThat(plan.lastDueMonth()).isEqualTo(YearMonth.of(2027, 5));
        assertThat(plan.status()).isEqualTo(InstallmentPlanStatus.ACTIVE);
        assertThat(plan.installments()).extracting(InstallmentPlanView.Item::status).containsExactly(
                InstallmentStatus.PAID, InstallmentStatus.PAID, InstallmentStatus.CURRENT, InstallmentStatus.FUTURE,
                InstallmentStatus.FUTURE, InstallmentStatus.FUTURE, InstallmentStatus.FUTURE, InstallmentStatus.FUTURE,
                InstallmentStatus.FUTURE, InstallmentStatus.FUTURE);
        // Limite reservado: R$ 3.840 na fatura de outubro (parcelas 3–10), R$ 480 na de maio.
        assertThat(plan.reservedLimit().get(2).amount()).isEqualTo(brl("3840.00"));
        assertThat(plan.reservedLimit().getLast().amount()).isEqualTo(brl("480.00"));

        assertThat(ctx.plans.list(ctx.anasWorkspaceId(), aurora.id(), null)).extracting(InstallmentPlanView::transactionId)
                .containsExactly(NOTEBOOK);
        assertThatThrownBy(() -> ctx.plans.get(ctx.anasWorkspaceId(), aurora.id(), UUID.randomUUID()))
                .isInstanceOf(TransactionNotFoundException.class);
        assertInvalidValue(() -> ctx.plans.list(ctx.anasWorkspaceId(), aurora.id(), "OPEN"), "status", "INVALID");
    }

    @Test
    void totalRefundOfTheNotebookCancelsFutureInstallmentsAndCreditsTheRest() {
        // ADR-0025 §8: estorno total em 22 set cancela as parcelas 4–10 (R$ 3.360) e credita R$ 1.440 em outubro.
        ctx.credit(ctx.storedCard(aurora.id()), LocalDate.parse("2026-09-22"), "4800.00", NOTEBOOK);

        UtilizationView utilization = ctx.utilization.execute(ctx.anasWorkspaceId(), aurora.id());
        assertThat(utilization.composition().futureInstallments()).isEqualTo(brl("0.00"));
        assertThat(utilization.composition().currentInvoice()).isEqualTo(brl("1972.80"));
        assertThat(utilization.utilizationPercent()).isEqualTo(16);

        InvoiceView october = ctx.getInvoice.current(ctx.anasWorkspaceId(), aurora.id());
        assertThat(october.invoice().charges()).isEqualTo(brl("3412.80"));
        assertThat(october.invoice().credits()).isEqualTo(brl("1440.00"));
        assertThat(ctx.listItems.execute(ctx.anasWorkspaceId(), aurora.id(), "2026-10", null, null).items())
                .filteredOn(item -> item.kind() == InvoiceItemKind.CREDIT).singleElement()
                .satisfies(credit -> {
                    assertThat(credit.amount()).isEqualTo(brl("4800.00"));
                    assertThat(credit.cancelledAmount()).isEqualTo(brl("3360.00"));
                });

        assertThat(ctx.future.execute(ctx.anasWorkspaceId(), aurora.id()).months()).isEmpty();
        InstallmentPlanView plan = ctx.plans.get(ctx.anasWorkspaceId(), aurora.id(), NOTEBOOK);
        assertThat(plan.status()).isEqualTo(InstallmentPlanStatus.ACTIVE);
        assertThat(plan.installments().subList(3, 10)).allSatisfy(item ->
                assertThat(item.status()).isEqualTo(InstallmentStatus.CANCELLED));
        assertThat(plan.remainingAfterCurrent()).isEqualTo(brl("0.00"));
    }

    @Test
    void unlinkedCreditGoesToTheInvoiceOfItsDate() {
        ctx.credit(ctx.storedCard(aurora.id()), LocalDate.parse("2026-09-10"), "100.00", null);

        InvoiceView october = ctx.getInvoice.current(ctx.anasWorkspaceId(), aurora.id());
        assertThat(october.invoice().amount()).isEqualTo(brl("3312.80"));
        assertThat(ctx.future.execute(ctx.anasWorkspaceId(), aurora.id()).totalCommitted()).isEqualTo(brl("3360.00"));
        assertThat(ctx.listItems.execute(ctx.anasWorkspaceId(), aurora.id(), "2026-10", null, null).items())
                .extracting(InvoiceItemView::kind).contains(InvoiceItemKind.CREDIT);
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
