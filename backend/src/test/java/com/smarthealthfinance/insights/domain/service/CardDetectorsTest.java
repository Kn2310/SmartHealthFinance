package com.smarthealthfinance.insights.domain.service;

import com.smarthealthfinance.cards.application.CardsTestContext;
import com.smarthealthfinance.cards.application.port.CardLedgerReader.InstallmentFact;
import com.smarthealthfinance.cards.application.service.CardPortfolioService;
import com.smarthealthfinance.cards.domain.model.CardInvoice;
import com.smarthealthfinance.cards.domain.model.CreditCard;
import com.smarthealthfinance.cards.domain.service.InstallmentSplitter;
import com.smarthealthfinance.cards.domain.service.InvoiceCalendar;
import com.smarthealthfinance.cards.domain.valueobject.CardPosition;
import com.smarthealthfinance.health.domain.enums.Confidence;
import com.smarthealthfinance.health.domain.enums.HealthDimension;
import com.smarthealthfinance.insights.domain.enums.ActionKind;
import com.smarthealthfinance.insights.domain.enums.Importance;
import com.smarthealthfinance.insights.domain.enums.InsightDetector;
import com.smarthealthfinance.insights.domain.enums.LimitationCode;
import com.smarthealthfinance.insights.domain.model.ChangeLine;
import com.smarthealthfinance.insights.domain.model.Contributor;
import com.smarthealthfinance.insights.domain.model.InsightCandidate;
import com.smarthealthfinance.insights.domain.model.InsightInput;
import com.smarthealthfinance.insights.domain.model.Limitation;
import com.smarthealthfinance.insights.domain.model.ScoredCandidate;
import com.smarthealthfinance.shared.domain.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * Detectores de Cartões da {@code insights-v2} (ADR-0028) com o Aurora de 09-dados-de-exemplo pela porta real de
 * Cards. Faturas de jul (2.710,40), ago (2.954,10) e set (3.012,10), cada uma paga no vencimento efetivo, média
 * R$ 2.892,20; outubro com 3.412,80 = +R$ 520,60 = alimentação fora +311,00 + parcela do notebook +160,00 (480 vs
 * média 320) + transporte +49,60.
 */
class CardDetectorsTest {

    private final CardsTestContext cards = new CardsTestContext();
    private final CardPortfolioService portfolio = new CardPortfolioService(cards.cards, cards.invoices,
            cards.ledger);
    private CreditCard aurora;

    @BeforeEach
    void aurora() {
        aurora = cards.storedCard(cards.createCard.execute(cards.anasWorkspaceId(), cards.auroraCommand()).id());
        // Notebook de 12 jul em 10 × 480 na Loja Tech: parcela 1 na fatura de agosto, 3 na de outubro.
        UUID notebook = UUID.randomUUID();
        List<Money> parts = InstallmentSplitter.split(brl("4800.00"), 10);
        for (int number = 1; number <= parts.size(); number++) {
            InvoiceCalendar calendar = new InvoiceCalendar(aurora.cycle(), cards.invoices.findByCard(
                    aurora.workspaceId(), aurora.id()).stream().map(CardInvoice::dates).toList());
            CardInvoice row = cards.invoiceRow(aurora, calendar.installmentClosingMonth(
                    LocalDate.parse("2026-07-12"), number));
            cards.ledger.installment(aurora.workspaceId(), aurora.id(), new InstallmentFact(notebook, number,
                    parts.size(), LocalDate.parse("2026-07-12"), parts.get(number - 1), row.id(), "Notebook",
                    "OTHER_EXPENSE", "Loja Tech", "MANUAL"));
        }
        // Compras à vista por fatura (mês de fechamento): alimentação fora, transporte e moradia.
        charges(YearMonth.of(2026, 6), "2026-06-10", "800.00", "150.40", "1760.00");
        charges(YearMonth.of(2026, 7), "2026-07-10", "820.00", "170.00", "1484.10");
        charges(YearMonth.of(2026, 8), "2026-08-10", "830.00", "180.00", "1522.10");
        charges(YearMonth.of(2026, 9), "2026-09-10", "1127.67", "216.40", "1588.73");
        cards.cards.recedeTrackingStart(aurora.workspaceId(), aurora.id(), YearMonth.of(2026, 6));
        pay("2026-07-06", "2710.40");
        pay("2026-08-05", "2954.10");
        pay("2026-09-07", "3012.10");
    }

    @Test
    void openInvoiceAbovePatternWithFactorsThatAddUpToTheDifference() {
        InsightCandidate insight = only(detect("2026-09-22"), InsightDetector.INVOICE_ABOVE_PATTERN);

        assertThat(insight.content().title()).isEqualTo(
                "Sua fatura do Aurora Platinum está 18% acima do seu padrão até agora, cerca de R$ 520 a mais.");
        assertThat(insight.content().support()).isEqualTo(
                "R$ 3.412,80 até 22 set, contra média de R$ 2.892,20 nas últimas 3 faturas.");
        assertThat(insight.periodKey()).isEqualTo("2026-10");
        assertThat(insight.subject()).isEqualTo(aurora.id().value().toString());
        assertThat(insight.impactAmount()).isEqualTo(brl("520.60"));
        assertThat(insight.partial()).isTrue();
        assertThat(insight.confidence()).isEqualTo(Confidence.MEDIUM);
        assertThat(insight.content().evidence().contributors())
                .extracting(Contributor::name, c -> c.amount().setScale(2))
                .containsExactly(tuple("Alimentação fora", new BigDecimal("311.00")),
                        tuple("Parcela de Loja Tech (3/10)", new BigDecimal("160.00")),
                        tuple("Transporte", new BigDecimal("49.60")));
        assertThat(insight.content().changeLine()).isEqualTo(new ChangeLine("Fatura do Aurora Platinum",
                "R$ 3.412,80 até 22 set", "+18% vs média das últimas 3"));
        assertThat(insight.content().recommendation().title()).isEqualTo("Revisar alimentação fora");
        assertThat(insight.content().evidence().limitations()).extracting(Limitation::code)
                .containsExactly(LimitationCode.PARTIAL_INVOICE);
        assertThat(insight.content().primaryAction().kind()).isEqualTo(ActionKind.VIEW_INVOICE);
        assertThat(insight.content().primaryAction().params()).containsEntry("dueMonth", "2026-10");
        assertThat(insight.content().refs().dimension()).isEqualTo(HealthDimension.CREDIT);
    }

    @Test
    void closedInvoiceStaysInFocusUntilItsDueDate() {
        // 01 out: outubro fechou em 28 set e vence em 05 out.
        InsightCandidate insight = only(detect("2026-10-01"), InsightDetector.INVOICE_ABOVE_PATTERN);

        assertThat(insight.content().title()).isEqualTo(
                "A fatura de outubro do Aurora Platinum fechou 18% acima do seu padrão, cerca de R$ 520 a mais.");
        assertThat(insight.content().support()).isEqualTo(
                "R$ 3.412,80, contra média de R$ 2.892,20 nas 3 faturas anteriores (jul–set).");
        assertThat(insight.confidence()).isEqualTo(Confidence.HIGH);
        assertThat(insight.partial()).isFalse();
    }

    @Test
    void aSmallDifferenceIsNotAnInsight() {
        // 01 set: a fatura em foco é a de setembro (vence em 05 set), 3.012,10 contra média de 2.832,25: só 6% acima.
        assertThat(detect("2026-09-01")).noneMatch(c -> c.detector() == InsightDetector.INVOICE_ABOVE_PATTERN);
    }

    @Test
    void highUtilizationComparesWithTheEndOfLastMonth() {
        InsightCandidate insight = only(detect("2026-09-22"), InsightDetector.CARD_UTILIZATION_HIGH);

        // 6.772,80 ÷ 12.000 = 56%; em 31 ago, 12.516,60 lançados − 5.664,50 pagos = 6.852,10 → 57%.
        assertThat(insight.content().title()).isEqualTo(
                "A utilização do Aurora Platinum está em 56% do limite, incluindo parcelas futuras.");
        assertThat(insight.content().support()).isEqualTo(
                "Era 57% no fim de agosto. Sem as parcelas futuras, seria 28%.");
        assertThat(insight.subject()).isEqualTo(aurora.id().value() + "|50-79");
        assertThat(insight.impactAmount()).isEqualTo(brl("772.80"));
        assertThat(insight.importantByRule()).isFalse();
        assertThat(insight.content().changeLine()).isEqualTo(new ChangeLine("Utilização do Aurora Platinum",
                "56% do limite", "era 57% em agosto"));
        assertThat(insight.content().evidence().series()).last()
                .satisfies(point -> assertThat(point.value()).isEqualByComparingTo("56"));
    }

    @Test
    void invoiceDueSoonAndThenOverdue() {
        InsightCandidate dueSoon = only(detect("2026-10-01"), InsightDetector.INVOICE_DUE);
        assertThat(dueSoon.content().title()).isEqualTo(
                "A fatura de outubro do Aurora Platinum vence em 5 out: R$ 3.412,80 em aberto.");
        assertThat(dueSoon.subject()).isEqualTo(aurora.id().value() + "|DUE_SOON");
        assertThat(dueSoon.importantByRule()).isFalse();
        assertThat(dueSoon.content().primaryAction().kind()).isEqualTo(ActionKind.RECORD_CARD_PAYMENT);

        InsightCandidate overdue = only(detect("2026-10-07"), InsightDetector.INVOICE_DUE);
        assertThat(overdue.content().title()).isEqualTo(
                "A fatura de outubro do Aurora Platinum venceu em 5 out com R$ 3.412,80 em aberto.");
        assertThat(overdue.subject()).isEqualTo(aurora.id().value() + "|OVERDUE");
        assertThat(overdue.importantByRule()).isTrue();

        // Pago no vencimento: nada a avisar.
        pay("2026-10-05", "3412.80");
        assertThat(detect("2026-10-07")).noneMatch(c -> c.detector() == InsightDetector.INVOICE_DUE);
    }

    @Test
    void anOverdueInvoiceIsImportant() {
        InsightInput input = input("2026-10-07");
        ScoredCandidate scored = InsightGeneratorScores.of(input).stream()
                .filter(s -> s.candidate().detector() == InsightDetector.INVOICE_DUE)
                .findFirst().orElseThrow();
        assertThat(scored.importance()).isEqualTo(Importance.IMPORTANT);
    }

    @Test
    void evaluatedInvoicesFollowTheFocusAndTheUnpaidInvoices() {
        String card = aurora.id().value().toString();
        assertThat(InsightEngine.evaluatedInvoices(input("2026-09-22"))).containsExactly(card + "|2026-10");
        assertThat(InsightEngine.evaluatedInvoices(input("2026-10-07"))).containsExactly(card + "|2026-11",
                card + "|2026-10");
    }

    private List<InsightCandidate> detect(String today) {
        return InsightEngine.detect(input(today));
    }

    /** A entrada como o loader monta: o fixture de 09-dados-de-exemplo mais os cartões de hoje e dos fins de mês. */
    private InsightInput input(String today) {
        LocalDate day = LocalDate.parse(today);
        InsightInput base = InsightFixture.example().asOf(today);
        Map<YearMonth, List<CardPosition>> monthEnds = new HashMap<>();
        for (int back = 1; back <= 6; back++) {
            LocalDate end = YearMonth.from(day).minusMonths(back).atEndOfMonth();
            monthEnds.put(YearMonth.from(end), positions(end));
        }
        return new InsightInput(base.period(), base.firstActivity(), base.ledger(), base.categoryNames(),
                base.largestExpenses(), base.health(), positions(day), monthEnds);
    }

    private List<CardPosition> positions(LocalDate date) {
        return portfolio.positions(cards.anasWorkspace.id(), List.of(date)).get(date);
    }

    private static InsightCandidate only(List<InsightCandidate> candidates, InsightDetector detector) {
        return candidates.stream().filter(c -> c.detector() == detector).reduce((a, b) -> {
            throw new AssertionError("Mais de um " + detector);
        }).orElseThrow(() -> new AssertionError("Nenhum " + detector));
    }

    private void charges(YearMonth closingMonth, String day, String dining, String transport, String housing) {
        CardInvoice row = cards.invoiceRow(aurora, closingMonth);
        charge(row, day, "DINING_OUT", "Bistrô Lume", dining);
        charge(row, day, "TRANSPORT", "Posto Via Norte", transport);
        charge(row, day, "HOUSING", null, housing);
    }

    private void charge(CardInvoice row, String day, String category, String merchant, String amount) {
        cards.ledger.installment(aurora.workspaceId(), aurora.id(), new InstallmentFact(UUID.randomUUID(), 1, 1,
                LocalDate.parse(day), brl(amount), row.id(), "Compra", category, merchant, "MANUAL"));
    }

    private void pay(String date, String amount) {
        cards.ledger.pay(aurora.workspaceId(), aurora.id(), LocalDate.parse(date), brl(amount));
    }

    private static Money brl(String amount) {
        return Money.of(amount, CardsTestContext.BRL);
    }

    /** Pontuação como o gerador faz, sem histórico guardado. */
    private static final class InsightGeneratorScores {
        static List<ScoredCandidate> of(InsightInput input) {
            Money reference = InsightEngine.reference(input);
            return InsightEngine.detect(input).stream()
                    .map(c -> InsightScoring.score(c, InsightFingerprint.of(c.detector(), InsightScoringTest.WORKSPACE,
                            c.periodKey(), c.subject()), reference, List.of(), InsightScoringTest.NOW))
                    .toList();
        }
    }
}
