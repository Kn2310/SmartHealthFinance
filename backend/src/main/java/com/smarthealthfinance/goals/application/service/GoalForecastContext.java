package com.smarthealthfinance.goals.application.service;

import com.smarthealthfinance.forecast.application.port.ForecastReader;
import com.smarthealthfinance.forecast.domain.enums.Limitation;
import com.smarthealthfinance.forecast.domain.model.ForecastResult;
import com.smarthealthfinance.forecast.domain.service.ForecastCalculator;
import com.smarthealthfinance.goals.domain.enums.GoalForecastFreshness;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.Money;
import org.springframework.stereotype.Service;

/**
 * O que a previsão dos objetivos lê do Forecast (ADR-0030 "Para a Sessão 7.2", ADR-0036): só pela porta
 * {@link ForecastReader}, sem calcular nada lá. Pronto = snapshot com histórico suficiente e capacidade de aporte;
 * senão a meta mostra só fatos.
 */
@Service
public class GoalForecastContext {

    private final ForecastReader forecast;

    public GoalForecastContext(ForecastReader forecast) {
        this.forecast = forecast;
    }

    /** @param capacity a capacidade de aporte central (Estimado), nula quando não está pronto */
    public record Snapshot(boolean ready, Money capacity, GoalForecastFreshness freshness) {}

    public Snapshot load(WorkspaceId workspace) {
        ForecastReader.SnapshotState state = forecast.state(workspace,
                ForecastCalculator.ALGORITHM_VERSION);
        GoalForecastFreshness freshness = !state.built() ? GoalForecastFreshness.NOT_BUILT
                : state.dirty() || state.pendingEvents() > 0 ? GoalForecastFreshness.RECALCULATING
                : GoalForecastFreshness.UP_TO_DATE;
        ForecastResult result = forecast.current(workspace).orElse(null);
        boolean ready = result != null && !result.limitations().contains(Limitation.INSUFFICIENT_HISTORY)
                && result.savingCapacity() != null;
        Money capacity = ready ? result.savingCapacity().monthly().value() : null;
        return new Snapshot(ready, capacity, freshness);
    }
}
