package com.smarthealthfinance.health.domain.service;

import com.smarthealthfinance.cards.application.CardsTestContext;
import com.smarthealthfinance.cards.application.port.CardLedgerReader.InstallmentFact;
import com.smarthealthfinance.cards.application.service.CardPortfolioService;
import com.smarthealthfinance.cards.application.usecase.CreateCard;
import com.smarthealthfinance.cards.domain.model.CardInvoice;
import com.smarthealthfinance.cards.domain.model.CreditCard;
import com.smarthealthfinance.cards.domain.service.InstallmentSplitter;
import com.smarthealthfinance.cards.domain.service.InvoiceCalendar;
import com.smarthealthfinance.cards.domain.valueobject.CardPosition;
import com.smarthealthfinance.shared.domain.Money;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/**
 * Cartões de 09-dados-de-exemplo pela porta real de Cards (ADR-0027 §1): Aurora Platinum (limite 12.000, fecha 28,
 * vence 05) com o notebook de 12 jul em 10 × R$ 480,00 na Loja Tech e as faturas de jul (2.710,40), ago (2.954,10) e
 * set (3.012,10), cada uma paga no vencimento efetivo; outubro aberta com 3.412,80. Horizonte Gold (limite 6.000) com
 * a fatura atual de 1.020,40, opcional.
 */
final class AuroraCards {

    static final UUID NOTEBOOK = UUID.randomUUID();

    private final CardsTestContext ctx = new CardsTestContext();
    private final CardPortfolioService portfolio = new CardPortfolioService(ctx.cards, ctx.invoices, ctx.ledger);
    private final CreditCard aurora;

    private AuroraCards(boolean payOnTime) {
        aurora = ctx.storedCard(ctx.createCard.execute(ctx.anasWorkspaceId(), ctx.auroraCommand()).id());
        List<Money> parts = InstallmentSplitter.split(brl("4800.00"), 10);
        for (int number = 1; number <= parts.size(); number++) {
            InvoiceCalendar calendar = new InvoiceCalendar(aurora.cycle(),
                    ctx.invoices.findByCard(aurora.workspaceId(), aurora.id()).stream().map(CardInvoice::dates)
                            .toList());
            CardInvoice row = ctx.invoiceRow(aurora,
                    calendar.installmentClosingMonth(LocalDate.parse("2026-07-12"), number));
            ctx.ledger.installment(aurora.workspaceId(), aurora.id(), new InstallmentFact(NOTEBOOK, number,
                    parts.size(), LocalDate.parse("2026-07-12"), parts.get(number - 1), row.id(), "Notebook",
                    "ELECTRONICS", "Loja Tech", "MANUAL"));
        }
        ctx.invoiceWith(aurora, YearMonth.of(2026, 6), "2710.40");
        ctx.invoiceWith(aurora, YearMonth.of(2026, 7), "2474.10");
        ctx.invoiceWith(aurora, YearMonth.of(2026, 8), "2532.10");
        ctx.invoiceWith(aurora, YearMonth.of(2026, 9), "2932.80");
        ctx.cards.recedeTrackingStart(aurora.workspaceId(), aurora.id(), YearMonth.of(2026, 6));
        pay("2026-07-06", "2710.40");
        pay("2026-08-05", "2954.10");
        if (payOnTime) {
            pay("2026-09-07", "3012.10");
        }
    }

    /** Todas as faturas pagas no vencimento efetivo (05 jul é domingo e 05 set é sábado). */
    static AuroraCards paidOnTime() {
        return new AuroraCards(true);
    }

    /** A fatura de setembro (vencimento efetivo 07 set) não foi paga. */
    static AuroraCards septemberUnpaid() {
        return new AuroraCards(false);
    }

    AuroraCards withHorizonte() {
        CreditCard horizonte = ctx.storedCard(ctx.createCard.execute(ctx.anasWorkspaceId(), new CreateCard.Command(
                "Horizonte Gold", "Conta Horizonte", "VISA", "0937", "6000.00", "BRL", 5, 12, null)).id());
        ctx.invoiceWith(horizonte, YearMonth.of(2026, 10), "1020.40");
        return this;
    }

    void pay(String date, String amount) {
        ctx.ledger.pay(aurora.workspaceId(), aurora.id(), LocalDate.parse(date), brl(amount));
    }

    List<CardPosition> on(LocalDate date) {
        return portfolio.positions(ctx.anasWorkspace.id(), List.of(date)).get(date);
    }

    private static Money brl(String amount) {
        return Money.of(amount, CardsTestContext.BRL);
    }
}
