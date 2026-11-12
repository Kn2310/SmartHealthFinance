package com.smarthealthfinance.copilot.infrastructure.tools;

import com.smarthealthfinance.copilot.domain.enums.PseudonymKind;
import com.smarthealthfinance.copilot.application.port.FinancialTool;
import com.smarthealthfinance.copilot.domain.enums.ActionKind;
import com.smarthealthfinance.copilot.domain.enums.DataFreshness;
import com.smarthealthfinance.copilot.domain.enums.Intent;
import com.smarthealthfinance.copilot.domain.enums.ValueKind;
import com.smarthealthfinance.copilot.domain.model.ContextValue;
import com.smarthealthfinance.copilot.domain.model.DataQuality;
import com.smarthealthfinance.copilot.domain.model.IntentDecision;
import com.smarthealthfinance.copilot.domain.model.OfferedAction;
import com.smarthealthfinance.copilot.domain.model.Pseudonyms;
import com.smarthealthfinance.copilot.domain.model.SourceRef;
import com.smarthealthfinance.goals.application.dto.SimulationView;
import com.smarthealthfinance.goals.application.service.GoalSimulator;
import com.smarthealthfinance.goals.application.usecase.SimulateGoal;
import com.smarthealthfinance.goals.domain.valueobject.SimulationScenario;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@code GOAL_SIMULATION} (ADR-0039 §4) sobre {@link SimulateGoal}, que não grava nada (ADR-0036 §2). Exige um
 * objetivo alvo e o cenário que a pessoa escreveu; sem um dos dois, {@code NEEDS_TARGET}. O que falta para a meta é
 * fato; o aporte, a redução e todos os resultados do cenário entram como {@code SIMULATED}. A categoria da redução
 * vai pelo nome do catálogo. Participação do aporte na renda em % inteiro, como o caso de uso devolve.
 */
@Component
public class GoalSimulationTool implements FinancialTool {

    static final String SOURCE = "goal-simulation-v1";

    private final SimulateGoal simulateGoal;

    public GoalSimulationTool(SimulateGoal simulateGoal) {
        this.simulateGoal = simulateGoal;
    }

    @Override
    public Intent intent() {
        return Intent.GOAL_SIMULATION;
    }

    @Override
    public Result collect(Request request) {
        Optional<Pseudonyms.Entry> goal = request.decision().targetValue()
                .flatMap(value -> request.pseudonyms().find(PseudonymKind.GOAL, value));
        Optional<IntentDecision.Scenario> scenario = request.decision().scenarioValue();
        if (goal.isEmpty() || scenario.isEmpty()) {
            return Result.of(Status.NEEDS_TARGET);
        }
        IntentDecision.Scenario input = scenario.get();
        SimulationView view = ToolSupport.lookup(() -> simulateGoal.execute(request.workspaceId(),
                UUID.fromString(goal.get().id()), List.of(new GoalSimulator.Scenario(input.monthlyAmount(),
                        input.deadline(), input.reductionCategory(), input.reductionAmount()))));
        SimulationScenario result = view.scenarios().getFirst().result();
        String tag = goal.get().pseudonym() + " (" + ToolSupport.goalType(goal.get().descriptor()) + ")";

        List<ContextValue> values = new ArrayList<>(new ToolValues.Builder(SOURCE)
                .money("Falta para a meta de " + tag, view.remaining(), null).build());
        ToolValues.Builder simulated = new ToolValues.Builder(SOURCE)
                .money("Aporte mensal simulado para " + tag, result.monthlyAmount(), ValueKind.SIMULATED, null)
                .money("Redução mensal de gastos simulada para " + tag, view.scenarios().getFirst().reductionAmount(),
                        ValueKind.SIMULATED, null)
                .text("Categoria da redução simulada para " + tag, view.scenarios().getFirst().reductionCategory() == null
                        ? null : request.categoryName(view.scenarios().getFirst().reductionCategory()), null)
                .month("Mês do primeiro aporte simulado para " + tag, result.firstContribution(),
                        ValueKind.SIMULATED)
                .month("Prazo considerado na simulação para " + tag, result.deadline(), ValueKind.SIMULATED)
                .month("Conclusão simulada de " + tag, result.completion(), ValueKind.SIMULATED)
                .count("Aportes necessários na simulação para " + tag, result.contributions(), null)
                .money("Último aporte simulado para " + tag, result.lastContributionAmount(), ValueKind.SIMULATED,
                        null)
                .money("Aporte mensal necessário para cumprir o prazo de " + tag, result.requiredMonthlyForDeadline(),
                        ValueKind.SIMULATED, null);
        if (result.monthsFromDeadline() != null) {
            simulated.count("Meses entre a conclusão simulada e o prazo de " + tag + ", negativo se antes",
                    result.monthsFromDeadline(), null);
        }
        if (result.withinDeadline() != null) {
            simulated.text("Cabe no prazo de " + tag + " na simulação", result.withinDeadline() ? "sim" : "não",
                    null);
        }
        if (result.incomeShare() != null) {
            simulated.percent("Aporte simulado sobre a renda média mensal", BigDecimal.valueOf(result.incomeShare()),
                    ValueKind.SIMULATED, null);
        }
        values.addAll(ToolSupport.as(simulated.build(), ValueKind.SIMULATED));

        List<String> gaps = result.tooFar() ? List.of("SIMULATION_TOO_FAR") : List.of();
        DataQuality quality = new DataQuality(null, false, gaps, DataFreshness.UP_TO_DATE, Set.of());
        return new Result(Status.DATA, values, quality, List.of(new SourceRef("SimulateGoal", SOURCE)),
                List.of(new OfferedAction(ActionKind.OPEN_GOAL, goal.get().pseudonym())));
    }
}
