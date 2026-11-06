package com.smarthealthfinance.goals.application.service;

import com.smarthealthfinance.goals.application.dto.SimulationView;
import com.smarthealthfinance.goals.domain.service.SimulationEngine;
import com.smarthealthfinance.goals.domain.valueobject.GoalPlan;
import com.smarthealthfinance.goals.domain.valueobject.SimulationScenario;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.InvalidValueException;
import com.smarthealthfinance.shared.domain.Money;
import org.springframework.stereotype.Service;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * Monta os cenários do {@link SimulationEngine} a partir das entradas da API (ADR-0034 §5, ADR-0036):
 * <ul>
 *   <li>aporte do cenário = ({@code monthlyAmount} ou o aporte planejado) + redução;</li>
 *   <li>só com {@code deadline} (sem aporte nem redução): o aporte necessário para o prazo;</li>
 *   <li>{@code deadline} ausente usa o prazo do objetivo (ou do rascunho).</li>
 * </ul>
 * A redução numa categoria só soma ao aporte: nenhum gasto muda.
 */
@Service
public class GoalSimulator {

    public static final int MAX_SCENARIOS = 5;

    private final GoalAverages averages;
    private final GoalCalendar calendar;

    public GoalSimulator(GoalAverages averages, GoalCalendar calendar) {
        this.averages = averages;
        this.calendar = calendar;
    }

    /** Valores textuais da API; nulos = não informados. */
    public record Scenario(String monthlyAmount, String deadline, String reductionCategory, String reductionAmount) {}

    public SimulationView simulate(WorkspaceId workspace, Money remaining, Money planned, YearMonth goalDeadline,
                                   List<Scenario> scenarios) {
        if (scenarios == null || scenarios.isEmpty() || scenarios.size() > MAX_SCENARIOS) {
            throw new InvalidValueException("scenarios", "OUT_OF_RANGE");
        }
        YearMonth currentMonth = YearMonth.from(calendar.today());
        List<Resolved> resolved = new ArrayList<>(scenarios.size());
        for (int i = 0; i < scenarios.size(); i++) {
            resolved.add(resolve("scenarios[" + i + "].", scenarios.get(i), remaining, planned, goalDeadline,
                    currentMonth));
        }
        Money income = averages.load(workspace).incomeIfAny().orElse(null);
        List<SimulationView.ScenarioView> results = new ArrayList<>(resolved.size());
        for (Resolved scenario : resolved) {
            SimulationScenario result = SimulationEngine.simulate(remaining, scenario.monthly(), currentMonth,
                    scenario.deadline(), income);
            results.add(new SimulationView.ScenarioView(result, scenario.reductionCategory(),
                    scenario.reductionAmount()));
        }
        return new SimulationView(remaining, SimulationEngine.start(currentMonth), income, results);
    }

    private record Resolved(Money monthly, YearMonth deadline, String reductionCategory, Money reductionAmount) {}

    private Resolved resolve(String prefix, Scenario scenario, Money remaining, Money planned, YearMonth goalDeadline,
                             YearMonth currentMonth) {
        YearMonth deadline = scenario.deadline() == null ? goalDeadline
                : GoalInputs.month(prefix + "deadline", scenario.deadline());
        Money reduction = null;
        if (scenario.reductionAmount() != null || scenario.reductionCategory() != null) {
            reduction = amount(prefix + "reduction.amount", scenario.reductionAmount(), true);
            if (!averages.isCategory(scenario.reductionCategory())) {
                throw new InvalidValueException(prefix + "reduction.categoryCode", "INVALID");
            }
        }
        if (scenario.monthlyAmount() == null && reduction == null && scenario.deadline() != null) {
            Money required = SimulationEngine.requiredMonthly(remaining, SimulationEngine.start(currentMonth),
                    deadline);
            if (required == null) {
                throw new InvalidValueException(prefix + "deadline", "PAST");
            }
            return new Resolved(required, deadline, null, null);
        }
        Money monthly = scenario.monthlyAmount() == null ? planned
                : amount(prefix + "monthlyAmount", scenario.monthlyAmount(), false);
        if (reduction != null) {
            monthly = monthly.plus(reduction);
        }
        return new Resolved(monthly, deadline, reduction == null ? null : scenario.reductionCategory(), reduction);
    }

    private static Money amount(String field, String value, boolean positive) {
        Money money = GoalInputs.money(field, value, GoalPlan.CURRENCY.getCurrencyCode());
        if (money.amount().signum() < 0) {
            throw new InvalidValueException(field, "NEGATIVE");
        }
        if (positive && !money.isPositive()) {
            throw new InvalidValueException(field, "MUST_BE_POSITIVE");
        }
        if (money.decimalPlaces() > GoalPlan.CURRENCY.getDefaultFractionDigits()) {
            throw new InvalidValueException(field, "TOO_MANY_DECIMALS");
        }
        return money;
    }
}
