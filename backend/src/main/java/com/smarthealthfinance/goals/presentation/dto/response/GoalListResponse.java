package com.smarthealthfinance.goals.presentation.dto.response;

import com.smarthealthfinance.goals.application.dto.GoalListView;
import com.smarthealthfinance.shared.presentation.money.MoneyDto;

import java.util.List;

/** Lista do D-Goals: o resumo (sempre dos objetivos abertos) e os objetivos, mais recentes primeiro (ADR-0035). */
public record GoalListResponse(GoalListSummary summary, List<GoalResponse> goals) {

    /**
     * @param savedTotal          guardado dos objetivos ativos e pausados
     * @param plannedMonthlyTotal aportes planejados por mês dos ativos
     * @param activeCount         objetivos ativos
     */
    public record GoalListSummary(MoneyDto savedTotal, MoneyDto plannedMonthlyTotal, int activeCount) {}

    public static GoalListResponse from(GoalListView view) {
        return new GoalListResponse(new GoalListSummary(GoalResponse.money(view.savedTotal()),
                GoalResponse.money(view.plannedMonthlyTotal()), view.activeCount()),
                view.goals().stream().map(GoalResponse::from).toList());
    }
}
