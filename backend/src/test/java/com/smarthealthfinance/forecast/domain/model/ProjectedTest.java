package com.smarthealthfinance.forecast.domain.model;

import com.smarthealthfinance.forecast.domain.enums.Basis;
import com.smarthealthfinance.forecast.domain.enums.Confidence;
import com.smarthealthfinance.forecast.domain.enums.Limitation;
import com.smarthealthfinance.shared.domain.Money;
import org.junit.jupiter.api.Test;

import java.util.Currency;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Taxonomia fato × estimativa e propagação da faixa (ADR-0030 §1, §4). */
class ProjectedTest {

    private static final Currency BRL = Currency.getInstance("BRL");

    private static Money brl(String value) {
        return Money.of(value, BRL);
    }

    private static Projected estimate(String fact, String central, String min, String max, Confidence confidence) {
        return Projected.estimated(brl(fact), brl(central), new Range(brl(min), brl(max)), confidence,
                List.of(), List.of());
    }

    @Test
    void aFactHasADegenerateRangeAndNoEstimatedPart() {
        Projected fact = Projected.fact(brl("3846.20"), true, List.of(), List.of());

        assertThat(fact.basis()).isEqualTo(Basis.FACT);
        assertThat(fact.partial()).isTrue();
        assertThat(fact.estimatedPart()).isEqualTo(brl("0"));
        assertThat(fact.low()).isEqualTo(fact.value()).isEqualTo(fact.high());
    }

    @Test
    void aTotalMixingFactAndEstimateIsEstimatedWithBothPartsAndAFactAddedToBothLimits() {
        Projected total = estimate("3846.20", "817.90", "700.00", "950.00", Confidence.MEDIUM);

        assertThat(total.basis()).isEqualTo(Basis.ESTIMATED);
        assertThat(total.value()).isEqualTo(brl("4664.10"));
        assertThat(total.factPart()).isEqualTo(brl("3846.20"));
        assertThat(total.estimatedPart()).isEqualTo(brl("817.90"));
        assertThat(total.low()).isEqualTo(brl("4546.20"));
        assertThat(total.high()).isEqualTo(brl("4796.20"));
        assertThat(total.partial()).isFalse();
    }

    @Test
    void estimatesAddMinimumsAndMaximumsAndTakeTheLowestConfidence() {
        Projected a = estimate("0", "100.00", "90.00", "120.00", Confidence.HIGH);
        Projected b = Projected.estimated(brl("0"), brl("50.00"), new Range(brl("40.00"), brl("70.00")),
                Confidence.LOW, List.of(Limitation.HIGH_VARIABILITY), List.of());

        Projected sum = a.plus(b);

        assertThat(sum.value()).isEqualTo(brl("150.00"));
        assertThat(sum.low()).isEqualTo(brl("130.00"));
        assertThat(sum.high()).isEqualTo(brl("190.00"));
        assertThat(sum.confidence()).isEqualTo(Confidence.LOW);
        assertThat(sum.limitations()).containsExactly(Limitation.HIGH_VARIABILITY);
    }

    @Test
    void subtractionIsConservative() {
        Projected income = Projected.fact(brl("6800.00"));
        Projected expense = estimate("3846.20", "817.90", "700.00", "950.00", Confidence.MEDIUM);

        Projected net = income.minus(expense);

        assertThat(net.basis()).isEqualTo(Basis.ESTIMATED);
        assertThat(net.value()).isEqualTo(brl("2135.90"));
        assertThat(net.factPart()).isEqualTo(brl("2953.80"));
        assertThat(net.estimatedPart()).isEqualTo(brl("-817.90"));
        // o limite de baixo desconta o limite de cima da despesa e vice-versa
        assertThat(net.low()).isEqualTo(brl("2003.80"));
        assertThat(net.high()).isEqualTo(brl("2253.80"));
    }

    @Test
    void twoFactsStayAFact() {
        Projected sum = Projected.fact(brl("10.00")).plus(Projected.fact(brl("5.00")));
        assertThat(sum.basis()).isEqualTo(Basis.FACT);
        assertThat(sum.value()).isEqualTo(brl("15.00"));
    }

    @Test
    void rejectsInconsistentValues() {
        assertThatThrownBy(() -> new Projected(Basis.SIMULATED, brl("1"), brl("1"), brl("0"), brl("1"), brl("1"),
                false, Confidence.HIGH, List.of(), List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Projected(Basis.ESTIMATED, brl("10"), brl("4"), brl("5"), brl("9"), brl("11"),
                false, Confidence.HIGH, List.of(), List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Projected(Basis.ESTIMATED, brl("10"), brl("5"), brl("5"), brl("11"), brl("12"),
                false, Confidence.HIGH, List.of(), List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Projected(Basis.FACT, brl("10"), brl("5"), brl("5"), brl("10"), brl("10"),
                false, Confidence.HIGH, List.of(), List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Projected(Basis.ESTIMATED, brl("10"), brl("5"), brl("5"), brl("9"), brl("11"),
                true, Confidence.HIGH, List.of(), List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void confidenceOrderingAndLowering() {
        assertThat(Confidence.HIGH.min(Confidence.LOW)).isEqualTo(Confidence.LOW);
        assertThat(Confidence.MEDIUM.cap(Confidence.HIGH)).isEqualTo(Confidence.MEDIUM);
        assertThat(Confidence.HIGH.lower()).isEqualTo(Confidence.MEDIUM);
        assertThat(Confidence.MEDIUM.lower()).isEqualTo(Confidence.LOW);
        assertThat(Confidence.LOW.lower()).isEqualTo(Confidence.LOW);
    }
}
