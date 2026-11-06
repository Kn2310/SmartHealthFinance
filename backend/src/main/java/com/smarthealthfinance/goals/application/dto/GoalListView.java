package com.smarthealthfinance.goals.application.dto;

import com.smarthealthfinance.shared.domain.Money;

import java.util.List;

/**
 * Lista de objetivos e o resumo do D-Goals (ADR-0035). O resumo é sempre dos objetivos abertos, qualquer que seja o
 * filtro da lista.
 *
 * @param savedTotal          soma do guardado dos objetivos ativos e pausados (os destinos nunca se sobrepõem)
 * @param plannedMonthlyTotal soma dos aportes planejados dos objetivos ativos
 * @param activeCount         objetivos ativos
 */
public record GoalListView(List<GoalView> goals, Money savedTotal, Money plannedMonthlyTotal, int activeCount) {

    public GoalListView {
        goals = List.copyOf(goals);
    }
}
