package com.smarthealthfinance.goals.application.dto;

import com.smarthealthfinance.goals.domain.valueobject.SimulationScenario;
import com.smarthealthfinance.shared.domain.Money;

import java.time.YearMonth;
import java.util.List;

/**
 * Resultado de uma simulação, sempre {@code SIMULATED} e nunca gravado (ADR-0034 §5, ADR-0036).
 *
 * @param remaining     quanto falta para o alvo
 * @param start         o mês do primeiro aporte
 * @param averageIncome a renda média usada no peso na renda (nula sem renda)
 * @param scenarios     na ordem pedida
 */
public record SimulationView(Money remaining, YearMonth start, Money averageIncome,
                             List<ScenarioView> scenarios) {

    public SimulationView {
        scenarios = List.copyOf(scenarios);
    }

    /**
     * @param reductionCategory a categoria da redução simulada, se houver (só rótulo: nenhum gasto muda)
     * @param reductionAmount   o valor somado ao aporte pela redução
     */
    public record ScenarioView(SimulationScenario result, String reductionCategory, Money reductionAmount) {}
}
