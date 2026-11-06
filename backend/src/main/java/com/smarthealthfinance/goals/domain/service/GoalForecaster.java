package com.smarthealthfinance.goals.domain.service;

import com.smarthealthfinance.goals.domain.enums.GoalConfidence;
import com.smarthealthfinance.goals.domain.enums.GoalForecastReason;
import com.smarthealthfinance.goals.domain.enums.GoalForecastStatus;
import com.smarthealthfinance.goals.domain.enums.GoalStatus;
import com.smarthealthfinance.goals.domain.valueobject.GoalForecast;
import com.smarthealthfinance.goals.domain.valueobject.SimulationScenario;
import com.smarthealthfinance.shared.domain.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Previsão de conclusão {@code goals-v1} (ADR-0034 §4), pura e determinística:
 * <ul>
 *   <li>pré-condições: Forecast pronto, objetivo ativo, alvo não alcançado e plano maior que zero;</li>
 *   <li>central pelo plano (a mesma conta do {@link SimulationEngine});</li>
 *   <li>faixa: otimista = central; pessimista pelo ritmo real quando ele é menor que o plano;</li>
 *   <li>confiança: {@code HIGH} com 3 meses fechados, ritmo ≥ plano e capacidade do Forecast ≥ soma dos planos;
 *   {@code LOW} com ritmo &lt; plano (≥ 1 mês fechado) ou capacidade &lt; soma dos planos; {@code MEDIUM} nos
 *   demais.</li>
 * </ul>
 */
public final class GoalForecaster {

    /** Meses fechados considerados no ritmo real. */
    public static final int PACE_MONTHS = 3;

    private GoalForecaster() {
    }

    /**
     * @param forecastReady     o Forecast está {@code READY} (com snapshot e histórico suficiente)
     * @param closedMonthlyNets aporte líquido (aportes − resgates) de cada mês fechado desde o início, do mais antigo
     *                          ao mais recente
     * @param capacity          a capacidade de aporte central do Forecast (nula sem Forecast)
     * @param plannedOfActive   a soma dos aportes planejados dos objetivos ativos do Workspace
     */
    public static GoalForecast forecast(GoalStatus status, Money remaining, boolean reached, Money planned,
                                        YearMonth deadline, YearMonth currentMonth, boolean forecastReady,
                                        List<Money> closedMonthlyNets, Money capacity, Money plannedOfActive) {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(remaining, "remaining");
        Objects.requireNonNull(planned, "planned");
        Objects.requireNonNull(currentMonth, "currentMonth");
        Objects.requireNonNull(closedMonthlyNets, "closedMonthlyNets");

        if (status != GoalStatus.ACTIVE) {
            return reached && status != GoalStatus.ARCHIVED ? GoalForecast.reached()
                    : GoalForecast.none(GoalForecastReason.NOT_ACTIVE);
        }
        if (reached) {
            return GoalForecast.reached();
        }
        if (!forecastReady) {
            return GoalForecast.none(GoalForecastReason.FORECAST_NOT_READY);
        }
        if (planned.amount().signum() == 0) {
            return GoalForecast.none(GoalForecastReason.NO_PLAN);
        }

        SimulationScenario central = SimulationEngine.simulate(remaining, planned, currentMonth, deadline, null);
        if (central.tooFar()) {
            return GoalForecast.none(GoalForecastReason.TOO_FAR);
        }
        YearMonth completion = central.completion();

        Money pace = median(lastN(closedMonthlyNets, PACE_MONTHS));
        YearMonth pessimistic = completion;
        boolean openEnded = false;
        if (pace != null && pace.amount().compareTo(planned.amount()) < 0) {
            if (pace.amount().signum() <= 0) {
                pessimistic = null;
                openEnded = true;
            } else {
                SimulationScenario slow = SimulationEngine.simulate(remaining, pace, currentMonth, deadline, null);
                pessimistic = slow.completion();
                openEnded = slow.tooFar();
            }
        }

        GoalForecastStatus forecastStatus;
        Integer late = null;
        Integer early = null;
        if (deadline == null) {
            forecastStatus = GoalForecastStatus.NO_DEADLINE;
        } else if (completion.isAfter(deadline)) {
            forecastStatus = GoalForecastStatus.LATE;
            late = (int) ChronoUnit.MONTHS.between(deadline, completion);
        } else {
            forecastStatus = GoalForecastStatus.ON_TRACK;
            early = (int) ChronoUnit.MONTHS.between(completion, deadline);
        }

        return new GoalForecast(forecastStatus, null, completion, central.contributions(), late, early, pessimistic,
                openEnded, confidence(closedMonthlyNets.size(), pace, planned, capacity, plannedOfActive), pace);
    }

    private static GoalConfidence confidence(int closedMonths, Money pace, Money planned, Money capacity,
                                             Money plannedOfActive) {
        boolean capacityShort = capacity == null || plannedOfActive == null
                || capacity.amount().compareTo(plannedOfActive.amount()) < 0;
        boolean paceShort = pace != null && pace.amount().compareTo(planned.amount()) < 0;
        if (paceShort || capacityShort) {
            return GoalConfidence.LOW;
        }
        if (closedMonths >= PACE_MONTHS && pace != null) {
            return GoalConfidence.HIGH;
        }
        return GoalConfidence.MEDIUM;
    }

    private static List<Money> lastN(List<Money> values, int n) {
        return new ArrayList<>(values.subList(Math.max(0, values.size() - n), values.size()));
    }

    /** Mediana; com quantidade par, a média dos dois do meio em centavos {@code HALF_EVEN}. Nula sem valores. */
    static Money median(List<Money> values) {
        if (values.isEmpty()) {
            return null;
        }
        List<Money> sorted = new ArrayList<>(values);
        sorted.sort((a, b) -> a.amount().compareTo(b.amount()));
        int middle = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(middle);
        }
        BigDecimal sum = sorted.get(middle - 1).amount().add(sorted.get(middle).amount());
        return new Money(sum.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_EVEN), sorted.get(middle).currency());
    }
}
