package com.smarthealthfinance.insights.domain.service;

import com.smarthealthfinance.forecast.domain.enums.Confidence;
import com.smarthealthfinance.forecast.domain.enums.Limitation;
import com.smarthealthfinance.forecast.domain.enums.UpcomingEventKind;
import com.smarthealthfinance.forecast.domain.model.CashFlowProjection;
import com.smarthealthfinance.forecast.domain.model.CashFlowProjection.BalancePoint;
import com.smarthealthfinance.forecast.domain.model.CashFlowProjection.Trough;
import com.smarthealthfinance.forecast.domain.model.Channel;
import com.smarthealthfinance.forecast.domain.model.ForecastResult;
import com.smarthealthfinance.forecast.domain.model.Projected;
import com.smarthealthfinance.forecast.domain.model.UpcomingEvent;
import com.smarthealthfinance.insights.domain.enums.ActionKind;
import com.smarthealthfinance.insights.domain.enums.Importance;
import com.smarthealthfinance.insights.domain.enums.InsightDetector;
import com.smarthealthfinance.insights.domain.enums.InsightType;
import com.smarthealthfinance.insights.domain.enums.LimitationCode;
import com.smarthealthfinance.insights.domain.model.Contributor;
import com.smarthealthfinance.insights.domain.model.InsightCandidate;
import com.smarthealthfinance.insights.domain.model.InsightInput;
import com.smarthealthfinance.insights.domain.model.ScoredCandidate;
import com.smarthealthfinance.shared.domain.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.smarthealthfinance.insights.domain.service.InsightScoringTest.NOW;
import static com.smarthealthfinance.insights.domain.service.InsightScoringTest.WORKSPACE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * D15 {@code BALANCE_MAY_GO_NEGATIVE} (ADR-0032 §3, ADR-0030 §8) sobre o exemplo de 09-dados-de-exemplo em 22/09/2026
 * (despesa média de referência R$ 3.668,50): gatilho, piso de R$ 100, confiança, importância, evidências e identidade.
 */
class ForecastDetectorsTest {

    private static final String TODAY = "2026-09-22";
    private static final LocalDate AS_OF = LocalDate.parse(TODAY);
    private final InsightFixture fixture = InsightFixture.example();

    private static Money brl(String amount) {
        return Money.of(amount, InsightFixture.BRL);
    }

    private static BalancePoint point(String date, String central, String low, String high) {
        return new BalancePoint(LocalDate.parse(date), brl(central), brl(low), brl(high));
    }

    /** Fluxo de caixa só com o necessário: o menor limite baixo e o menor central dos próximos 30 dias. */
    private static ForecastResult forecast(BalancePoint lowestLow, BalancePoint lowestCentral, Confidence confidence,
                                           List<UpcomingEvent> events, List<Limitation> limitations) {
        CashFlowProjection cashFlow = new CashFlowProjection(AS_OF, LocalDate.parse("2026-12-31"), List.of(),
                new Trough(lowestCentral, lowestLow), new Trough(lowestCentral, lowestLow), confidence, limitations,
                List.of());
        return new ForecastResult("forecast-v1", AS_OF, 5, List.of(), List.of(), null, null, events, List.of(),
                cashFlow, null);
    }

    private static ForecastResult negative(String low, String central, Confidence confidence) {
        BalancePoint lowest = point("2026-10-03", central, low, "900.00");
        return forecast(lowest, lowest, confidence, List.of(), List.of());
    }

    private List<InsightCandidate> detect(ForecastResult forecast) {
        return InsightEngine.detect(fixture.asOf(TODAY, forecast)).stream()
                .filter(c -> c.detector() == InsightDetector.BALANCE_MAY_GO_NEGATIVE).toList();
    }

    private List<ScoredCandidate> score(ForecastResult forecast) {
        return InsightGeneratorScores.of(fixture.asOf(TODAY, forecast));
    }

    @Test
    void aNegativeLowBoundWithEnoughDeficitAndConfidenceIsDetected() {
        InsightCandidate d15 = detect(negative("-500.00", "-120.00", Confidence.MEDIUM)).getFirst();

        assertThat(d15.detector().type()).isEqualTo(InsightType.SPENDING);
        assertThat(d15.content().title()).isEqualTo("Seu saldo pode ficar negativo por volta de 3 out.");
        assertThat(d15.impactAmount()).isEqualTo(brl("500.00"));
        assertThat(d15.periodKey()).isNull();
        assertThat(d15.confidence()).isEqualTo(com.smarthealthfinance.health.domain.enums.Confidence.MEDIUM);
        assertThat(d15.importantByRule()).isTrue();
        assertThat(d15.content().actions()).singleElement().satisfies(action -> {
            assertThat(action.kind()).isEqualTo(ActionKind.VIEW_UPCOMING_EVENTS);
            assertThat(action.label()).isEqualTo("Ver próximos eventos");
            assertThat(action.primary()).isTrue();
            assertThat(action.params()).isEmpty();
        });
        assertThat(d15.content().evidence().figures()).extracting("code", "value")
                .contains(org.assertj.core.groups.Tuple.tuple("lowBound", new java.math.BigDecimal("-500.0000")),
                        org.assertj.core.groups.Tuple.tuple("central", new java.math.BigDecimal("-120.0000")),
                        org.assertj.core.groups.Tuple.tuple("highBound", new java.math.BigDecimal("900.0000")));
        assertThat(d15.content().evidence().limitations()).extracting("code")
                .containsExactly(LimitationCode.ESTIMATED_FORECAST);
    }

    @ParameterizedTest(name = "limite baixo {0} → detecta = {1}")
    @CsvSource({"-99.99,false", "-100.00,true", "-100.01,true", "0.00,false", "250.00,false"})
    void theDeficitFloorIsOneHundred(String low, boolean detected) {
        assertThat(detect(negative(low, low.startsWith("-") ? low : "900.00", Confidence.HIGH)).size())
                .isEqualTo(detected ? 1 : 0);
    }

    @ParameterizedTest(name = "confiança {0} → detecta = {1}")
    @CsvSource({"HIGH,true", "MEDIUM,true", "LOW,false"})
    void theCashFlowConfidenceMustBeAtLeastMedium(Confidence confidence, boolean detected) {
        assertThat(detect(negative("-500.00", "-120.00", confidence)).size()).isEqualTo(detected ? 1 : 0);
    }

    @Test
    void importantWhenTheCentralValueIsNegativeAndInformativeWhenOnlyTheLowBoundIs() {
        ScoredCandidate both = score(negative("-500.00", "-120.00", Confidence.HIGH)).getFirst();
        ScoredCandidate onlyLow = score(negative("-500.00", "50.00", Confidence.HIGH)).getFirst();

        assertThat(both.candidate().detector()).isEqualTo(InsightDetector.BALANCE_MAY_GO_NEGATIVE);
        assertThat(both.importance()).isEqualTo(Importance.IMPORTANT);
        assertThat(onlyLow.candidate().detector()).isEqualTo(InsightDetector.BALANCE_MAY_GO_NEGATIVE);
        assertThat(onlyLow.importance()).isEqualTo(Importance.INFORMATIVE);
        assertThat(onlyLow.impact()).isEqualTo(both.impact());
    }

    @Test
    void theImpactIsTheDeficitOverTheAverageExpense() {
        // R = 3.668,50 (média das despesas); déficit 500 → 13,6% → I = 80 + (3,6/10 × 20) ≈ 87 pela curva do ADR-0021 §3.
        ScoredCandidate d15 = score(negative("-500.00", "-120.00", Confidence.HIGH)).getFirst();
        int expected = InsightScoring.impact(d15.candidate(), InsightEngine.reference(fixture.asOf(TODAY)));

        assertThat(d15.impact()).isEqualTo(expected).isBetween(80, 100);
    }

    @Test
    void withoutSnapshotWithoutCashFlowOrWithInsufficientHistoryNothingIsDetected() {
        assertThat(InsightEngine.detect(fixture.asOf(TODAY))).extracting(InsightCandidate::detector)
                .doesNotContain(InsightDetector.BALANCE_MAY_GO_NEGATIVE);

        ForecastResult noCashFlow = new ForecastResult("forecast-v1", AS_OF, 1, List.of(Limitation.INSUFFICIENT_HISTORY),
                List.of(), null, null, List.of(), List.of(), null, null);
        assertThat(detect(noCashFlow)).isEmpty();

        ForecastResult insufficient = new ForecastResult("forecast-v1", AS_OF, 2,
                List.of(Limitation.INSUFFICIENT_HISTORY), List.of(), null, null, List.of(), List.of(),
                negative("-500.00", "-120.00", Confidence.HIGH).cashFlow(), null);
        assertThat(detect(insufficient)).isEmpty();
    }

    @Test
    void theIdentityIsStableWhileTheRiskPersistsEvenIfTheDateMoves() {
        InsightCandidate today = detect(negative("-500.00", "-120.00", Confidence.HIGH)).getFirst();
        BalancePoint moved = point("2026-10-09", "-300.00", "-800.00", "100.00");
        InsightCandidate tomorrow = detect(forecast(moved, moved, Confidence.MEDIUM, List.of(), List.of())).getFirst();

        assertThat(tomorrow.content().title()).isNotEqualTo(today.content().title());
        assertThat(fingerprint(tomorrow)).isEqualTo(fingerprint(today));
        assertThat(fingerprint(today)).isNotEqualTo(InsightFingerprint.of(InsightDetector.BALANCE_MAY_GO_NEGATIVE,
                UUID.randomUUID(), null, ForecastDetectors.SUBJECT));
    }

    @Test
    void theEvidenceListsTheThreeLargestMovementsUpToTheDate() {
        UUID card = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        List<UpcomingEvent> events = List.of(
                event(UpcomingEventKind.RECURRING_EXPENSE, "2026-09-25", "Aluguel", "1800.00", Channel.account(account)),
                event(UpcomingEventKind.RECURRING_INCOME, "2026-09-30", "Salário", "900.00", Channel.account(account)),
                event(UpcomingEventKind.INVOICE_OPEN, "2026-10-02", "Cartão Aurora", "3412.80", Channel.card(card)),
                event(UpcomingEventKind.RECURRING_EXPENSE, "2026-10-01", "Streaming", "39.90", Channel.account(account)),
                event(UpcomingEventKind.RECURRING_EXPENSE, "2026-10-01", "Assinatura no cartão", "5000.00",
                        Channel.card(card)),
                event(UpcomingEventKind.RECURRING_EXPENSE, "2026-10-10", "Depois da data", "9000.00",
                        Channel.account(account)));
        BalancePoint lowest = point("2026-10-03", "-120.00", "-500.00", "900.00");

        InsightCandidate d15 = detect(forecast(lowest, lowest, Confidence.MEDIUM, events, List.of())).getFirst();

        assertThat(d15.content().evidence().contributors()).extracting(Contributor::name, Contributor::amount,
                        Contributor::day)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Cartão Aurora", new java.math.BigDecimal("-3412.8000"),
                                LocalDate.parse("2026-10-02")),
                        org.assertj.core.groups.Tuple.tuple("Aluguel", new java.math.BigDecimal("-1800.0000"),
                                LocalDate.parse("2026-09-25")),
                        org.assertj.core.groups.Tuple.tuple("Salário", new java.math.BigDecimal("900.0000"),
                                LocalDate.parse("2026-09-30")));
    }

    @Test
    void shortHistoryAndUncategorizedLimitationsArePassedOn() {
        BalancePoint lowest = point("2026-10-03", "-120.00", "-500.00", "900.00");
        InsightCandidate d15 = detect(forecast(lowest, lowest, Confidence.MEDIUM, List.of(),
                List.of(Limitation.SHORT_HISTORY, Limitation.UNCATEGORIZED))).getFirst();

        assertThat(d15.content().evidence().limitations()).extracting("code").containsExactly(
                LimitationCode.ESTIMATED_FORECAST, LimitationCode.SHORT_HISTORY, LimitationCode.UNCATEGORIZED);
    }

    private static UpcomingEvent event(UpcomingEventKind kind, String date, String label, String value,
                                       Channel channel) {
        return new UpcomingEvent(kind, LocalDate.parse(date), null, label, channel, Projected.fact(brl(value)), null);
    }

    private static String fingerprint(InsightCandidate c) {
        return InsightFingerprint.of(c.detector(), WORKSPACE, c.periodKey(), c.subject());
    }

    /** Pontuação como o gerador faz, sem histórico guardado. */
    private static final class InsightGeneratorScores {
        static List<ScoredCandidate> of(InsightInput input) {
            Money reference = InsightEngine.reference(input);
            return InsightEngine.detect(input).stream()
                    .filter(c -> c.detector() == InsightDetector.BALANCE_MAY_GO_NEGATIVE)
                    .map(c -> InsightScoring.score(c, InsightFingerprint.of(c.detector(), WORKSPACE, c.periodKey(),
                            c.subject()), reference, List.of(), NOW))
                    .toList();
        }
    }
}
