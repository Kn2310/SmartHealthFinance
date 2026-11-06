package com.smarthealthfinance.goals.domain.service;

import com.smarthealthfinance.goals.domain.valueobject.SimulationScenario;
import com.smarthealthfinance.shared.domain.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.YearMonth;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;

/** Motor de simulação {@code goals-v1} (ADR-0034 §5) com os números de 09-dados-de-exemplo; hoje = set/2026. */
class SimulationEngineTest {

    private static final Currency BRL = Currency.getInstance("BRL");
    private static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);
    private static final YearMonth JULY_2027 = YearMonth.of(2027, 7);

    @ParameterizedTest(name = "faltam {0}, R$ {1}/mês → {2} aportes, {3}")
    @CsvSource({
            // Simulação Lisboa (faltam R$ 7.700, prazo jul/2027): 700 / 780 / 900 → ago / jul / jun 2027
            "7700.00, 700.00, 11, 2027-08, 700.00, false, 1",
            "7700.00, 780.00, 10, 2027-07, 680.00, true, 0",
            "7700.00, 900.00, 9, 2027-06, 500.00, true, -1",
            // O mínimo exato para o prazo
            "7700.00, 770.00, 10, 2027-07, 770.00, true, 0"
    })
    void lisbonTable(String remaining, String monthly, int contributions, String completion, String last,
                     boolean within, int fromDeadline) {
        SimulationScenario scenario = SimulationEngine.simulate(money(remaining), money(monthly), SEPTEMBER,
                JULY_2027, money("6560.00"));

        assertThat(scenario.contributions()).isEqualTo(contributions);
        assertThat(scenario.completion()).isEqualTo(YearMonth.parse(completion));
        assertThat(scenario.firstContribution()).isEqualTo(YearMonth.of(2026, 10));
        assertThat(scenario.lastContributionAmount()).isEqualTo(money(last));
        assertThat(scenario.withinDeadline()).isEqualTo(within);
        assertThat(scenario.monthsFromDeadline()).isEqualTo(fromDeadline);
        assertThat(scenario.requiredMonthlyForDeadline()).isEqualTo(money("770.00"));
    }

    @Test
    void reserveAndDeliveryFromTheSampleData() {
        // Reserva: faltam R$ 15.152 com R$ 800/mês → 19 aportes, abr/2028.
        SimulationScenario reserve = SimulationEngine.simulate(money("15152.00"), money("800.00"), SEPTEMBER, null,
                null);
        assertThat(reserve.contributions()).isEqualTo(19);
        assertThat(reserve.completion()).isEqualTo(YearMonth.of(2028, 4));
        assertThat(reserve.lastContributionAmount()).isEqualTo(money("752.00"));
        assertThat(reserve.withinDeadline()).isNull();

        // Delivery (D-CreateGoal): R$ 1.800 com R$ 300/mês → 6 aportes, out/2026–mar/2027; peso ≈ 5% da renda.
        SimulationScenario delivery = SimulationEngine.simulate(money("1800.00"), money("300.00"), SEPTEMBER,
                YearMonth.of(2027, 3), money("6600.00"));
        assertThat(delivery.contributions()).isEqualTo(6);
        assertThat(delivery.completion()).isEqualTo(YearMonth.of(2027, 3));
        assertThat(delivery.withinDeadline()).isTrue();
        assertThat(delivery.incomeShare()).isEqualTo(5);
    }

    @Test
    void incomeShareRoundsHalfUpAndIsAbsentWithoutIncome() {
        assertThat(SimulationEngine.incomeShare(money("1500.00"), money("6600.00"))).isEqualTo(23);
        assertThat(SimulationEngine.incomeShare(money("1800.00"), money("6600.00"))).isEqualTo(27);
        assertThat(SimulationEngine.incomeShare(money("300.00"), money("6000.00"))).isEqualTo(5);
        assertThat(SimulationEngine.incomeShare(money("300.00"), null)).isNull();
        assertThat(SimulationEngine.incomeShare(money("300.00"), Money.zero(BRL))).isNull();
    }

    @Test
    void zeroContributionReachedTargetAndTooFarHaveNoCompletion() {
        assertThat(SimulationEngine.simulate(money("100.00"), Money.zero(BRL), SEPTEMBER, null, null).completion())
                .isNull();

        SimulationScenario reached = SimulationEngine.simulate(Money.zero(BRL), money("100.00"), SEPTEMBER,
                JULY_2027, null);
        assertThat(reached.completion()).isNull();
        assertThat(reached.contributions()).isZero();
        assertThat(reached.withinDeadline()).isTrue();

        SimulationScenario far = SimulationEngine.simulate(money("1000000.00"), money("1.00"), SEPTEMBER, null, null);
        assertThat(far.tooFar()).isTrue();
        assertThat(far.completion()).isNull();
    }

    @Test
    void requiredMonthlyRoundsUpCentsAndIsAbsentForADeadlineBeforeTheStart() {
        YearMonth start = SimulationEngine.start(SEPTEMBER);
        assertThat(SimulationEngine.requiredMonthly(money("1000.00"), start, YearMonth.of(2026, 12)))
                .isEqualTo(money("333.34"));
        assertThat(SimulationEngine.requiredMonthly(money("1000.00"), start, SEPTEMBER)).isNull();
    }

    private static Money money(String amount) {
        return Money.of(amount, BRL);
    }
}
