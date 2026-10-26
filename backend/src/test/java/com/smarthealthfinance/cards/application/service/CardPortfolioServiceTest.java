package com.smarthealthfinance.cards.application.service;

import com.smarthealthfinance.cards.application.CardsTestContext;
import com.smarthealthfinance.cards.application.dto.CardView;
import com.smarthealthfinance.cards.application.usecase.CreateCard;
import com.smarthealthfinance.cards.domain.enums.InvoiceItemKind;
import com.smarthealthfinance.cards.domain.enums.InvoiceStatus;
import com.smarthealthfinance.cards.domain.model.CreditCard;
import com.smarthealthfinance.cards.domain.service.InstallmentSplitter;
import com.smarthealthfinance.cards.domain.valueobject.CardPosition;
import com.smarthealthfinance.cards.domain.valueobject.CardPosition.DueInvoice;
import com.smarthealthfinance.cards.domain.valueobject.CardPosition.InstallmentCommitment;
import com.smarthealthfinance.cards.domain.valueobject.CardPosition.InvoiceLine;
import com.smarthealthfinance.shared.domain.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.smarthealthfinance.cards.application.CardsTestContext.BRL;
import static org.assertj.core.api.Assertions.assertThat;

/** Posição dos cartões numa data (ADR-0027 §1), com os cartões de 09-dados-de-exemplo e hoje = 22 set 2026. */
class CardPortfolioServiceTest {

    private static final UUID NOTEBOOK = UUID.randomUUID();
    private static final LocalDate TODAY = LocalDate.parse("2026-09-22");

    private final CardsTestContext ctx = new CardsTestContext();
    private final CardPortfolioService portfolio = new CardPortfolioService(ctx.cards, ctx.invoices, ctx.ledger);
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
        ctx.invoiceWith(card, YearMonth.of(2026, 6), "2710.40");
        ctx.invoiceWith(card, YearMonth.of(2026, 7), "2474.10");
        ctx.invoiceWith(card, YearMonth.of(2026, 8), "2532.10");
        ctx.invoiceWith(card, YearMonth.of(2026, 9), "2932.80");
        // Um único pagamento em 05 set quitou julho, agosto e setembro: só setembro foi paga em dia.
        ctx.ledger.pay(card.workspaceId(), card.id(), LocalDate.parse("2026-09-05"), brl("8676.60"));
        ctx.cards.recedeTrackingStart(card.workspaceId(), card.id(), YearMonth.of(2026, 6));

        ctx.invoiceWith(ctx.storedCard(horizonte.id()), YearMonth.of(2026, 10), "1020.40");
    }

    @Test
    void todayMatchesTheCardReadings() {
        List<CardPosition> today = positionsOn(TODAY);

        assertThat(today).extracting(position -> position.cardId().value())
                .containsExactly(aurora.id(), horizonte.id());
        CardPosition auroraToday = today.getFirst();
        assertThat(auroraToday.nickname()).isEqualTo("Aurora Platinum");
        assertThat(auroraToday.utilization().utilized()).isEqualTo(brl("6772.80"));
        assertThat(auroraToday.utilization().utilizationPercent()).isEqualTo(56);
        assertThat(auroraToday.futureInstallments()).isEqualTo(brl("3360.00"));
        assertThat(auroraToday.current().amount()).isEqualTo(brl("3412.80"));
        assertThat(auroraToday.current().status()).isEqualTo(InvoiceStatus.OPEN);
        assertThat(auroraToday.nextInvoiceCommitted()).isEqualTo(brl("480.00"));
        assertThat(auroraToday.commitments()).containsExactly(new InstallmentCommitment(NOTEBOOK, null, null, 10, 7,
                brl("3360.00"), YearMonth.of(2027, 5)));
    }

    @Test
    void punctualityIsTheLedgerOnEachEffectiveDueDate() {
        List<DueInvoice> due = positionsOn(TODAY).getFirst().dueInvoices();

        // 05 jul é domingo e 05 set é sábado: vencimentos efetivos na segunda-feira.
        assertThat(due).extracting(DueInvoice::dueMonth, DueInvoice::effectiveDueDate, DueInvoice::amount,
                        DueInvoice::paidOnTime)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(YearMonth.of(2026, 7), LocalDate.parse("2026-07-06"),
                                brl("2710.40"), false),
                        org.assertj.core.groups.Tuple.tuple(YearMonth.of(2026, 8), LocalDate.parse("2026-08-05"),
                                brl("2954.10"), false),
                        org.assertj.core.groups.Tuple.tuple(YearMonth.of(2026, 9), LocalDate.parse("2026-09-07"),
                                brl("3012.10"), true));
    }

    @Test
    void anEarlierDateOnlySeesTheFactsUntilThen() {
        CardPosition before = positionsOn(LocalDate.parse("2026-09-04")).getFirst();

        // Sem o pagamento de 05 set: tudo o que foi lançado até 04 set está em aberto.
        assertThat(before.asOf()).isEqualTo(LocalDate.parse("2026-09-04"));
        assertThat(before.utilization().utilized()).isEqualTo(brl("15449.40"));
        assertThat(before.unpaidClosed()).extracting(statement -> statement.dates().dueMonth())
                .containsExactly(YearMonth.of(2026, 7), YearMonth.of(2026, 8), YearMonth.of(2026, 9));
        assertThat(before.current().dates().dueMonth()).isEqualTo(YearMonth.of(2026, 10));
    }

    @Test
    void aCardOnlyCountsAfterItsTrackingStarted() {
        // Horizonte começa na fatura que fecha em 05 out (período desde 05 set); Aurora em junho (desde 28 mai).
        assertThat(positionsOn(LocalDate.parse("2026-08-31"))).extracting(position -> position.cardId().value())
                .containsExactly(aurora.id());
        assertThat(positionsOn(LocalDate.parse("2026-05-27"))).isEmpty();
    }

    @Test
    void linesNetTheCreditsAppliedToTheInvoice() {
        CreditCard card = ctx.storedCard(aurora.id());
        ctx.credit(card, LocalDate.parse("2026-09-10"), "100.00", null);

        List<InvoiceLine> october = positionsOn(TODAY).getFirst().lines().get(YearMonth.of(2026, 9));

        assertThat(october).filteredOn(line -> line.kind() == InvoiceItemKind.CREDIT)
                .extracting(InvoiceLine::net).containsExactly(brl("100.00"));
        assertThat(october).filteredOn(line -> line.kind() == InvoiceItemKind.CHARGE)
                .extracting(InvoiceLine::net).containsExactlyInAnyOrder(brl("2932.80"), brl("480.00"));
    }

    @Test
    void archivedCardsAndOtherWorkspacesAreLeftOut() {
        ctx.archiveCard.execute(ctx.anasWorkspaceId(), horizonte.id());

        assertThat(positionsOn(TODAY)).extracting(position -> position.cardId().value())
                .containsExactly(aurora.id());
        assertThat(portfolio.positions(ctx.bobsWorkspace.id(), List.of(TODAY)).get(TODAY)).isEmpty();
    }

    private List<CardPosition> positionsOn(LocalDate date) {
        Map<LocalDate, List<CardPosition>> positions = portfolio.positions(ctx.anasWorkspace.id(), List.of(date));
        return positions.get(date);
    }

    private static Money brl(String amount) {
        return Money.of(amount, BRL);
    }
}
