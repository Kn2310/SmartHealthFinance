package com.smarthealthfinance.forecast.domain.service;

import com.smarthealthfinance.forecast.domain.enums.Confidence;
import com.smarthealthfinance.forecast.domain.enums.Limitation;
import com.smarthealthfinance.forecast.domain.enums.SeriesDirection;
import com.smarthealthfinance.forecast.domain.model.Range;
import com.smarthealthfinance.transactions.domain.enums.CashFlowRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import static com.smarthealthfinance.forecast.domain.service.ForecastFixture.brl;
import static com.smarthealthfinance.forecast.domain.service.ForecastFixture.date;
import static org.assertj.core.api.Assertions.assertThat;

/** Confiança e limitações (ADR-0030 §5) e a incerteza (§4). */
class ConfidenceRulesTest {

    /** Primeiro lançamento → meses fechados (hoje = 22/09/2026). */
    @ParameterizedTest(name = "primeiro lançamento {0} → {1} meses → {2}")
    @CsvSource({
            "2026-09-01, 0, INSUFFICIENT_HISTORY",
            "2026-07-31, 2, INSUFFICIENT_HISTORY",
            "2026-06-01, 3, SHORT_HISTORY",
            "2026-04-30, 5, SHORT_HISTORY",
            "2026-03-01, 6, NONE",
            "2025-01-01, 20, NONE",
    })
    void historyMonthsAndLimitations(String first, int months, String limitation) {
        int history = ConfidenceRules.historyMonths(Optional.of(date(first)), date("2026-09-22"));
        assertThat(history).isEqualTo(months);
        assertThat(ConfidenceRules.historyLimitations(history))
                .isEqualTo(limitation.equals("NONE") ? List.of() : List.of(Limitation.valueOf(limitation)));
        assertThat(ConfidenceRules.sufficient(history)).isEqualTo(months >= 3);
    }

    @Test
    void noActivityMeansNoHistory() {
        assertThat(ConfidenceRules.historyMonths(Optional.empty(), date("2026-09-22"))).isZero();
    }

    @Test
    void shortHistoryCapsAtMediumAndHighVariabilityAtLow() {
        assertThat(ConfidenceRules.sampleConfidence(6, false)).isEqualTo(Confidence.HIGH);
        assertThat(ConfidenceRules.sampleConfidence(5, false)).isEqualTo(Confidence.MEDIUM);
        assertThat(ConfidenceRules.sampleConfidence(6, true)).isEqualTo(Confidence.LOW);
        assertThat(ConfidenceRules.finalConfidence(List.of(Confidence.HIGH, Confidence.HIGH), 4, false))
                .isEqualTo(Confidence.MEDIUM);
    }

    @Test
    void uncategorizedLowersOneLevelAndTheConfidenceOfASumIsTheLowestPart() {
        assertThat(ConfidenceRules.finalConfidence(List.of(Confidence.HIGH, Confidence.HIGH), 6, true))
                .isEqualTo(Confidence.MEDIUM);
        assertThat(ConfidenceRules.finalConfidence(List.of(Confidence.HIGH, Confidence.LOW), 6, false))
                .isEqualTo(Confidence.LOW);
        assertThat(ConfidenceRules.finalConfidence(List.of(), 6, false)).isEqualTo(Confidence.HIGH);
    }

    /** Mais de 15% da despesa sem categoria (estritamente). */
    @ParameterizedTest(name = "sem categoria {0} de 100 → {1}")
    @CsvSource({"14.99, false", "15.00, false", "15.01, true", "0, false"})
    void uncategorizedThreshold(String uncategorized, boolean exceeded) {
        ForecastFixture fixture = new ForecastFixture()
                .add("2026-08-10", CashFlowRole.EXPENSE, "GROCERIES", "mercado",
                        brl("100.00").minus(brl(uncategorized)).toPlainString());
        if (!uncategorized.equals("0")) {
            fixture.add("2026-08-11", CashFlowRole.EXPENSE, null, "lojinha", uncategorized);
        }
        assertThat(ConfidenceRules.uncategorizedExceeded(fixture.entries(), List.of(YearMonth.of(2026, 8))))
                .isEqualTo(exceeded);
    }

    /** Amplitude acima de 25% do valor central. */
    @ParameterizedTest(name = "central {0} faixa {1}–{2} → {3}")
    @CsvSource({
            "100.00, 90.00,  115.00, false",
            "100.00, 90.00,  115.01, true",
            "100.00, 75.00,  100.00, false",
            "100.00, 70.00,  100.00, true",
            "0,      0,      0,      false",
            "0,      0,      1.00,   true",
    })
    void highVariability(String central, String min, String max, boolean expected) {
        assertThat(Statistics.highVariability(brl(central), new Range(brl(min), brl(max)))).isEqualTo(expected);
    }

    @Test
    void estimatedMedianIsTheCentralValueAndRangeIsMinMax() {
        List<com.smarthealthfinance.shared.domain.Money> samples = List.of(brl("2710.40"), brl("2474.10"),
                brl("2532.10"));
        assertThat(Statistics.median(samples)).isEqualTo(brl("2532.10"));
        assertThat(Statistics.rangeOf(samples)).isEqualTo(new Range(brl("2474.10"), brl("2710.40")));
    }

    @Test
    void directionEnumOrderPutsIncomeFirst() {
        assertThat(SeriesDirection.values()).containsExactly(SeriesDirection.INCOME, SeriesDirection.EXPENSE);
    }
}
