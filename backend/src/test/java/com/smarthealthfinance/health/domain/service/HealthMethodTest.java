package com.smarthealthfinance.health.domain.service;

import com.smarthealthfinance.health.domain.enums.HealthDimension;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/** O "Como calculamos?" sai das mesmas constantes do cálculo (ADR-0019). */
class HealthMethodTest {

    private final HealthMethod.Description method = HealthMethod.describe();

    @Test
    void weightsAreTheCalculatorWeightsAndSumOneHundred() {
        assertThat(method.dimensions()).extracting(HealthMethod.Dimension::dimension)
                .containsExactly(HealthDimension.values());
        assertThat(method.dimensions()).allSatisfy(d -> assertThat(d.weight()).isEqualTo(d.dimension().weight()));
        assertThat(Arrays.stream(HealthDimension.values()).mapToInt(HealthDimension::weight).sum()).isEqualTo(100);
    }

    @Test
    void curvesAreTheOnesUsedInTheScore() {
        HealthMethod.Dimension reserve = method.dimensions().get(4);
        assertThat(reserve.dimension()).isEqualTo(HealthDimension.RESERVE);
        assertThat(reserve.curves()).singleElement().satisfies(curve -> assertThat(curve.points())
                .extracting(p -> p.x().toPlainString() + "→" + p.score().toPlainString())
                .containsExactly("0→0", "1→45", "3→80", "6→100"));
        assertThat(method.dimensions().get(1).curves()).hasSize(2);
    }

    @Test
    void creditAndDebtApplyFromHealthV2() {
        assertThat(method.dimensions()).allMatch(HealthMethod.Dimension::applicable);
        assertThat(method.dimensions().get(2).curves()).singleElement().satisfies(curve -> assertThat(curve.points())
                .extracting(p -> p.x().toPlainString() + "→" + p.score().toPlainString())
                .containsExactly("0→100", "0.30→80", "0.60→55", "0.90→20", "1→0"));
        assertThat(method.dimensions().get(3).curves()).hasSize(2);
        assertThat(method.exclusions()).noneMatch(exclusion -> exclusion.startsWith("Cartões"));
        assertThat(method.algorithmVersion()).isEqualTo(HealthCalculator.ALGORITHM_VERSION);
        assertThat(method.estimated()).contains("3 meses");
    }
}
