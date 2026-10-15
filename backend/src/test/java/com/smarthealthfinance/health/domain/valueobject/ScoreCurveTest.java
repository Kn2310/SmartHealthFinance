package com.smarthealthfinance.health.domain.valueobject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Interpolação linear por pontos com saturação nas pontas (ADR-0017 §1). */
class ScoreCurveTest {

    /** Curva do Cash Flow: −10% → 0 · 0% → 30 · 10% → 60 · 20% → 80 · ≥ 40% → 100. */
    private static final ScoreCurve CASH_FLOW = ScoreCurve.of("-0.10", "0", "0", "30", "0.10", "60", "0.20", "80",
            "0.40", "100");
    /** Curva decrescente do Δ do Spending: ≤ 0 → 100 · +10% → 75 · +25% → 40 · ≥ +50% → 0. */
    private static final ScoreCurve SPENDING_VARIATION = ScoreCurve.of("0", "100", "0.10", "75", "0.25", "40",
            "0.50", "0");

    @ParameterizedTest
    @CsvSource({
            "-0.50,  0.0000",
            "-0.10,  0.0000",
            "-0.05,  15.0000",
            "0,      30.0000",
            "0.05,   45.0000",
            "0.10,   60.0000",
            "0.15,   70.0000",
            "0.3610, 96.1000",
            "0.40,   100.0000",
            "0.95,   100.0000",
    })
    void interpolatesBetweenPointsAndSaturatesOutside(String x, String expected) {
        assertThat(CASH_FLOW.score(new BigDecimal(x))).isEqualByComparingTo(expected).hasScaleOf(4);
    }

    @ParameterizedTest
    @CsvSource({"-0.20, 100.0000", "0.1043, 73.9967", "0.30, 32.0000", "0.75, 0.0000"})
    void decreasingCurvesRoundToFourPlaces(String x, String expected) {
        assertThat(SPENDING_VARIATION.score(new BigDecimal(x))).isEqualByComparingTo(expected).hasScaleOf(4);
    }

    @Test
    void rejectsInvalidCurves() {
        assertThatThrownBy(() -> ScoreCurve.of("0", "0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScoreCurve.of("1", "0", "0", "10")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScoreCurve.of("1", "0", "2")).isInstanceOf(IllegalArgumentException.class);
    }
}
