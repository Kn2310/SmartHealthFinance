package com.smarthealthfinance.overview.domain.model;

import com.smarthealthfinance.cards.application.CardsTestContext;
import com.smarthealthfinance.cards.application.dto.CardView;
import com.smarthealthfinance.cards.application.dto.CardsSummaryView;
import com.smarthealthfinance.cards.application.service.CardPortfolioService;
import com.smarthealthfinance.cards.application.usecase.CreateCard;
import com.smarthealthfinance.cards.domain.model.CreditCard;
import com.smarthealthfinance.cards.domain.service.InstallmentSplitter;
import com.smarthealthfinance.cards.domain.valueobject.CardPosition;
import com.smarthealthfinance.overview.domain.enums.UpcomingEventKind;
import com.smarthealthfinance.overview.domain.enums.UpcomingEventStatus;
import com.smarthealthfinance.overview.domain.model.CardDebts.UpcomingEvent;
import com.smarthealthfinance.shared.domain.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static com.smarthealthfinance.cards.application.CardsTestContext.BRL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * "Faturas a pagar" e "Próximos eventos" (ADR-0006 §18) com os cartões de 09-dados-de-exemplo e hoje = 22 set 2026.
 * O bloco usa o mesmo razão do resumo de cartões, então os totais têm de bater com ele.
 */
class CardDebtsTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-09-22");

    private final CardsTestContext ctx = new CardsTestContext();
    private final CardPortfolioService portfolio = new CardPortfolioService(ctx.cards, ctx.invoices, ctx.ledger);
    private CreditCard aurora;
    private CreditCard horizonte;

    @BeforeEach
    void sampleData() {
        CardView auroraView = ctx.createCard.execute(ctx.anasWorkspaceId(), ctx.auroraCommand());
        CardView horizonteView = ctx.createCard.execute(ctx.anasWorkspaceId(), new CreateCard.Command(
                "Horizonte Gold", "Conta Horizonte", "VISA", "0937", "6000.00", "BRL", 5, 12, null));
        aurora = ctx.storedCard(auroraView.id());
        horizonte = ctx.storedCard(horizonteView.id());

        ctx.purchase(aurora, UUID.randomUUID(), LocalDate.parse("2026-07-12"), "Notebook",
                InstallmentSplitter.split(brl("4800.00"), 10));
        ctx.invoiceWith(aurora, YearMonth.of(2026, 6), "2710.40");
        ctx.invoiceWith(aurora, YearMonth.of(2026, 7), "2474.10");
        ctx.invoiceWith(aurora, YearMonth.of(2026, 8), "2532.10");
        ctx.invoiceWith(aurora, YearMonth.of(2026, 9), "2932.80");
        ctx.cards.recedeTrackingStart(aurora.workspaceId(), aurora.id(), YearMonth.of(2026, 6));
        ctx.invoiceWith(horizonte, YearMonth.of(2026, 10), "1020.40");
    }

    @Test
    void openInvoicesAndNextDueMatchTheCardsSummary() {
        ctx.ledger.pay(aurora.workspaceId(), aurora.id(), LocalDate.parse("2026-09-05"), brl("8676.60"));

        CardDebts debts = debts();
        CardsSummaryView summary = ctx.summary.execute(ctx.anasWorkspaceId());

        assertThat(debts.activeCards()).isEqualTo(2);
        assertThat(debts.openInvoices()).isEqualTo(brl("4433.20")).isEqualTo(summary.openInvoices());
        assertThat(debts.nextDue().date()).isEqualTo(LocalDate.parse("2026-10-05")).isEqualTo(summary.nextDueDate());
        assertThat(debts.nextDue().amount()).isEqualTo(brl("3412.80"));
        assertThat(debts.asOf()).isEqualTo(TODAY);
    }

    @Test
    void upcomingEventsAreOnlyFactsOfTheNextThirtyDays() {
        ctx.ledger.pay(aurora.workspaceId(), aurora.id(), LocalDate.parse("2026-09-05"), brl("8676.60"));

        assertThat(debts().upcomingEvents())
                .extracting(UpcomingEvent::date, UpcomingEvent::kind, UpcomingEvent::status, UpcomingEvent::amount,
                        event -> event.card().nickname())
                .containsExactly(
                        tuple(LocalDate.parse("2026-09-28"), UpcomingEventKind.INVOICE_CLOSING,
                                UpcomingEventStatus.PARTIAL, brl("3412.80"), "Aurora Platinum"),
                        tuple(LocalDate.parse("2026-10-05"), UpcomingEventKind.INVOICE_CLOSING,
                                UpcomingEventStatus.PARTIAL, brl("1020.40"), "Horizonte Gold"),
                        tuple(LocalDate.parse("2026-10-05"), UpcomingEventKind.INVOICE_DUE,
                                UpcomingEventStatus.PARTIAL, brl("3412.80"), "Aurora Platinum"),
                        tuple(LocalDate.parse("2026-10-12"), UpcomingEventKind.INVOICE_DUE,
                                UpcomingEventStatus.PARTIAL, brl("1020.40"), "Horizonte Gold"));
    }

    @Test
    void closedAndOverdueInvoicesShowWhatIsStillOpen() {
        // Só julho e agosto pagos: setembro venceu em 05 set (efetivo 07) com 3.012,10 em aberto.
        ctx.ledger.pay(aurora.workspaceId(), aurora.id(), LocalDate.parse("2026-08-05"), brl("5664.50"));

        CardDebts debts = debts();

        assertThat(debts.upcomingEvents()).first()
                .extracting(UpcomingEvent::date, UpcomingEvent::kind, UpcomingEvent::status, UpcomingEvent::dueMonth,
                        UpcomingEvent::amount)
                .containsExactly(LocalDate.parse("2026-09-05"), UpcomingEventKind.INVOICE_DUE,
                        UpcomingEventStatus.OVERDUE, YearMonth.of(2026, 9), brl("3012.10"));
        assertThat(debts.nextDue().date()).isEqualTo(LocalDate.parse("2026-09-05"));
        assertThat(debts.openInvoices()).isEqualTo(brl("7445.30"));
    }

    @Test
    void anInvoiceClosedBeforeItsDueDateShowsTheOpenBalance() {
        // Em 30 set, a fatura de outubro do Aurora já fechou (28 set) e vence em 05 out.
        ctx.ledger.pay(aurora.workspaceId(), aurora.id(), LocalDate.parse("2026-09-05"), brl("8676.60"));
        List<CardPosition> positions = portfolio.positions(ctx.anasWorkspace.id(), List.of(LocalDate.parse("2026-09-30")))
                .get(LocalDate.parse("2026-09-30"));

        CardDebts debts = CardDebts.of(positions, LocalDate.parse("2026-09-30"), BRL);

        assertThat(debts.upcomingEvents())
                .filteredOn(event -> event.card().nickname().equals("Aurora Platinum")
                        && event.kind() == UpcomingEventKind.INVOICE_DUE)
                .extracting(UpcomingEvent::date, UpcomingEvent::status, UpcomingEvent::amount)
                .containsExactly(tuple(LocalDate.parse("2026-10-05"), UpcomingEventStatus.CLOSED, brl("3412.80")));
    }

    @Test
    void withoutCardsThereIsNoBlock() {
        assertThat(CardDebts.of(List.of(), TODAY, BRL)).isNull();
    }

    private CardDebts debts() {
        return CardDebts.of(portfolio.positions(ctx.anasWorkspace.id(), List.of(TODAY)).get(TODAY), TODAY, BRL);
    }

    private static Money brl(String amount) {
        return Money.of(amount, BRL);
    }
}
