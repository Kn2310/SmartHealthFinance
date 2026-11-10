package com.smarthealthfinance.copilot.infrastructure.tools;

import com.smarthealthfinance.copilot.domain.enums.PseudonymKind;
import com.smarthealthfinance.copilot.application.port.FinancialTool;
import com.smarthealthfinance.copilot.domain.enums.ActionKind;
import com.smarthealthfinance.copilot.domain.enums.DataFreshness;
import com.smarthealthfinance.copilot.domain.enums.Intent;
import com.smarthealthfinance.copilot.domain.enums.ValueKind;
import com.smarthealthfinance.copilot.domain.model.DataQuality;
import com.smarthealthfinance.copilot.domain.model.OfferedAction;
import com.smarthealthfinance.copilot.domain.model.Pseudonyms;
import com.smarthealthfinance.copilot.domain.model.SourceRef;
import com.smarthealthfinance.goals.application.dto.GoalListView;
import com.smarthealthfinance.goals.application.dto.GoalView;
import com.smarthealthfinance.goals.application.usecase.GetGoal;
import com.smarthealthfinance.goals.application.usecase.GetGoalProjection;
import com.smarthealthfinance.goals.application.usecase.ListGoals;
import com.smarthealthfinance.goals.domain.enums.GoalForecastFreshness;
import com.smarthealthfinance.goals.domain.valueobject.GoalForecast;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@code GOALS} (ADR-0039 §4) sobre {@link ListGoals} (até 5 objetivos abertos) ou, com um objetivo alvo,
 * {@link GetGoal} e {@link GetGoalProjection}. Cada objetivo vira {@code objetivo_N (tipo)}: meta, guardado,
 * restante, percentual (inteiro, 0 a 100), aporte mensal planejado, prazo e previsão de conclusão
 * ({@code ESTIMATED}). O nome digitado do objetivo nunca entra. Com o alvo, entram também o guardado dos últimos 3
 * meses e o valor projetado na conclusão.
 */
@Component
public class GoalsTool implements FinancialTool {

    static final String SOURCE = "goals-v1";
    static final int MAX_GOALS = 5;
    static final int ACTUAL_MONTHS = 3;

    private final ListGoals listGoals;
    private final GetGoal getGoal;
    private final GetGoalProjection getProjection;

    public GoalsTool(ListGoals listGoals, GetGoal getGoal, GetGoalProjection getProjection) {
        this.listGoals = listGoals;
        this.getGoal = getGoal;
        this.getProjection = getProjection;
    }

    @Override
    public Intent intent() {
        return Intent.GOALS;
    }

    @Override
    public Result collect(Request request) {
        Optional<UUID> target = request.decision().targetValue()
                .flatMap(value -> request.pseudonyms().find(PseudonymKind.GOAL, value))
                .map(entry -> UUID.fromString(entry.id()));
        ToolValues.Builder values = new ToolValues.Builder(SOURCE);
        List<OfferedAction> actions = new ArrayList<>();
        List<GoalView> goals;
        GoalForecastFreshness freshness;
        List<SourceRef> sources = new ArrayList<>();
        if (target.isPresent()) {
            GoalView goal = getGoal.execute(request.workspaceId(), target.get());
            goals = List.of(goal);
            freshness = goal.forecastFreshness();
            sources.add(new SourceRef("GetGoal", SOURCE));
            sources.add(new SourceRef("GetGoalProjection", SOURCE));
        } else {
            GoalListView list = listGoals.execute(request.workspaceId(), null);
            if (list.goals().isEmpty()) {
                return Result.of(Status.NO_DATA);
            }
            goals = list.goals().stream().limit(MAX_GOALS).toList();
            freshness = list.forecastFreshness();
            sources.add(new SourceRef("ListGoals", SOURCE));
            values.money("Total guardado nos objetivos abertos", list.savedTotal(), null)
                    .money("Total de aportes mensais planejados dos objetivos ativos", list.plannedMonthlyTotal(),
                            null);
            if (list.plannedIncomeShare() != null) {
                values.percent("Aportes planejados sobre a renda média mensal",
                        BigDecimal.valueOf(list.plannedIncomeShare()), null);
            }
        }
        for (GoalView goal : goals) {
            String pseudonym = ToolValues.goal(request.pseudonyms(), goal);
            goal(values, pseudonym + " (" + ToolSupport.goalType(goal.type().name()) + ")", goal);
            actions.add(new OfferedAction(ActionKind.OPEN_GOAL, pseudonym));
        }
        if (target.isPresent()) {
            GoalView goal = goals.getFirst();
            projection(values, ToolValues.goal(request.pseudonyms(), goal) + " ("
                    + ToolSupport.goalType(goal.type().name()) + ")",
                    getProjection.execute(request.workspaceId(), goal.id()));
        }
        DataQuality quality = new DataQuality(null, false, List.of(), freshness(freshness), Set.of());
        return new Result(Status.DATA, values.build(), quality, sources, actions);
    }

    private static void goal(ToolValues.Builder values, String tag, GoalView goal) {
        values.money("Meta de " + tag, goal.targetAmount(), null)
                .money("Guardado em " + tag, goal.progress().saved(), null)
                .money("Falta para a meta de " + tag, goal.progress().remaining(), null)
                .percent("Progresso de " + tag, BigDecimal.valueOf(goal.progress().percent()), null)
                .money("Aporte mensal planejado de " + tag, goal.plannedMonthly(), null)
                .month("Prazo de " + tag, goal.deadline(), ValueKind.FACT);
        GoalForecast forecast = goal.forecast();
        if (forecast != null) {
            values.month("Previsão de conclusão de " + tag, forecast.completion(), ValueKind.ESTIMATED)
                    .text("Situação da previsão de " + tag, forecastText(forecast), null);
        }
        values.text("Situação de " + tag, switch (goal.status()) {
            case ACTIVE -> "ativo";
            case PAUSED -> "pausado";
            case COMPLETED -> "concluído";
            case ARCHIVED -> "arquivado";
        }, null);
    }

    private static void projection(ToolValues.Builder values, String tag, GetGoalProjection.Projection projection) {
        List<GetGoalProjection.Point> actual = projection.actual();
        actual.subList(Math.max(0, actual.size() - ACTUAL_MONTHS), actual.size()).forEach(point -> values.money(
                "Guardado no fim do mês em " + tag, point.amount(), ToolValues.month(point.month())));
        if (!projection.projected().isEmpty()) {
            GetGoalProjection.Point last = projection.projected().getLast();
            values.money("Valor projetado na conclusão de " + tag, last.amount(), ValueKind.ESTIMATED,
                    ToolValues.month(last.month()));
        }
    }

    private static String forecastText(GoalForecast forecast) {
        return switch (forecast.status()) {
            case ON_TRACK -> "dentro do prazo";
            case LATE -> "atrasado em relação ao prazo";
            case NO_DEADLINE -> "sem prazo definido";
            case REACHED -> "meta alcançada";
            case NO_FORECAST -> "sem previsão";
        };
    }

    private static DataFreshness freshness(GoalForecastFreshness freshness) {
        return switch (freshness) {
            case UP_TO_DATE -> DataFreshness.UP_TO_DATE;
            case RECALCULATING -> DataFreshness.UPDATING;
            case NOT_BUILT -> DataFreshness.NOT_BUILT;
        };
    }
}
