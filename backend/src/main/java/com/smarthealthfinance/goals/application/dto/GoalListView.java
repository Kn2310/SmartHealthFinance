package com.smarthealthfinance.goals.application.dto;

import com.smarthealthfinance.goals.domain.enums.GoalForecastFreshness;
import com.smarthealthfinance.shared.domain.Money;

import java.util.List;

/**
 * Lista de objetivos e o resumo do D-Goals (ADR-0035, ADR-0036). O resumo é sempre dos objetivos abertos, qualquer
 * que seja o filtro da lista.
 *
 * @param savedTotal          soma do guardado dos objetivos ativos e pausados (os destinos nunca se sobrepõem)
 * @param plannedMonthlyTotal soma dos aportes planejados dos objetivos ativos
 * @param activeCount         objetivos ativos
 * @param plannedIncomeShare  aportes planejados ÷ renda média mensal (janela do Health), % inteiro {@code HALF_UP};
 *                            nulo sem renda. Exibido como "≈ 23% da sua renda média"
 * @param forecastCount       ativos com previsão (Estimado)
 * @param onTrackCount        dentre eles, os no prazo ou sem prazo ("1 de 2 dentro do prazo ou sem prazo")
 * @param forecastFreshness   a atualização do Forecast em que as previsões se apoiam
 */
public record GoalListView(List<GoalView> goals, Money savedTotal, Money plannedMonthlyTotal, int activeCount,
                           Integer plannedIncomeShare, int forecastCount, int onTrackCount,
                           GoalForecastFreshness forecastFreshness) {

    public GoalListView {
        goals = List.copyOf(goals);
    }
}
