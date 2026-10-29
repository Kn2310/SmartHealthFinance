package com.smarthealthfinance.forecast.domain.service;

import com.smarthealthfinance.forecast.domain.enums.Basis;
import com.smarthealthfinance.forecast.domain.enums.Confidence;
import com.smarthealthfinance.forecast.domain.enums.Limitation;
import com.smarthealthfinance.forecast.domain.model.ForecastInput;
import com.smarthealthfinance.forecast.domain.model.RecurringSeries;
import com.smarthealthfinance.forecast.domain.model.SavingCapacity;
import com.smarthealthfinance.transactions.domain.enums.CashFlowRole;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import static com.smarthealthfinance.forecast.domain.service.ForecastFixture.brl;
import static org.assertj.core.api.Assertions.assertThat;

/** Capacidade de aporte mensal (ADR-0030 §7). */
class SavingCapacityProjectorTest {

    private static Optional<SavingCapacity> project(ForecastInput input) {
        List<RecurringSeries> detected = RecurrenceDetector.detect(input.entries(), input.today());
        List<RecurringSeries> merged = RecurrenceMerge.merge(input.existingSeries(), detected);
        return SavingCapacityProjector.project(ProjectionContext.of(input,
                SeriesAssessor.assess(merged, detected, input.entries(), input.today())));
    }

    @Test
    void junePlanIsIncomeMinusRentMinusVariable() {
        SavingCapacity capacity = project(JuneScenario.input()).orElseThrow();

        // 5.000,00 − aluguel 1.000,00 [950–1.050] − variável 300,00 (2 × 150,00) = 3.700,00 [3.650–3.750]
        assertThat(capacity.months()).extracting(m -> m.month().toString())
                .containsExactly("2026-06", "2026-07", "2026-08", "2026-09");
        assertThat(capacity.months()).allSatisfy(m -> {
            assertThat(m.central()).isEqualTo(brl("3700.00"));
            assertThat(m.low()).isEqualTo(brl("3650.00"));
            assertThat(m.high()).isEqualTo(brl("3750.00"));
            assertThat(m.committedInstallments()).isEqualTo(brl("0"));
        });
        assertThat(capacity.monthly().basis()).isEqualTo(Basis.ESTIMATED);
        assertThat(capacity.monthly().value()).isEqualTo(brl("3700.00"));
        assertThat(capacity.monthly().low()).isEqualTo(brl("3650.00"));
        assertThat(capacity.monthly().high()).isEqualTo(brl("3750.00"));
        assertThat(capacity.monthly().factPart()).isEqualTo(brl("0"));
        assertThat(capacity.monthly().confidence()).isEqualTo(Confidence.MEDIUM);
        assertThat(capacity.monthly().limitations()).containsExactly(Limitation.SHORT_HISTORY);
        assertThat(capacity.monthly().evidence()).isNotEmpty();
    }

    @Test
    void committedInstallmentsAreSubtractedAsFactAndTheVariableExcludesInstallmentPurchases() {
        ForecastFixture fixture = new ForecastFixture()
                .addCard("2026-06-24", "GROCERIES", "loja a", "100.00", ForecastFixture.AURORA, false)
                .addCard("2026-07-26", "GROCERIES", "loja b", "80.00", ForecastFixture.AURORA, false)
                .addCard("2026-08-25", "GROCERIES", "loja c", "120.00", ForecastFixture.AURORA, false)
                // a compra parcelada do notebook não é gasto variável: entra como parcela comprometida
                .addCard("2026-07-12", "ELECTRONICS", "loja tech", "4800.00", ForecastFixture.AURORA, true);
        ForecastInput input = fixture.input("2026-09-22", "2026-06-01", "18420.35", List.of(),
                List.of(ForecastFixture.aurora()), List.of());

        SavingCapacity capacity = project(input).orElseThrow();

        // faturas por mês de vencimento: set (fechada em ago) 480, out (aberta) 480, nov e dez (futuras) 480
        assertThat(capacity.months()).allSatisfy(m -> assertThat(m.committedInstallments()).isEqualTo(brl("480.00")));
        assertThat(capacity.months().getFirst().month()).isEqualTo(YearMonth.of(2026, 9));
        // variável: mediana de jun/jul/ago = 100,00 [80–120]
        assertThat(capacity.monthly().value()).isEqualTo(brl("-580.00"));
        assertThat(capacity.monthly().factPart()).isEqualTo(brl("-480.00"));
        assertThat(capacity.monthly().estimatedPart()).isEqualTo(brl("-100.00"));
        assertThat(capacity.monthly().low()).isEqualTo(brl("-600.00"));
        assertThat(capacity.monthly().high()).isEqualTo(brl("-560.00"));
    }

    @Test
    void withoutEnoughHistoryThereIsNoCapacity() {
        ForecastFixture fixture = new ForecastFixture().add("2026-09-10", CashFlowRole.EXPENSE, "GROCERIES",
                "mercado", "100.00");
        assertThat(project(fixture.input("2026-09-22", "2026-09-10", "0"))).isEmpty();
    }
}
