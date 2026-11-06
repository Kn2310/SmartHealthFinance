package com.smarthealthfinance.goals.presentation.dto.response;

import com.smarthealthfinance.goals.application.dto.GoalListView;
import com.smarthealthfinance.shared.presentation.money.MoneyDto;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Lista do D-Goals: o resumo (sempre dos objetivos abertos) e os objetivos, mais recentes primeiro (ADR-0035,
 * ADR-0036).
 */
public record GoalListResponse(GoalListSummary summary, List<GoalResponse> goals) {

    /**
     * @param savedTotal          guardado dos objetivos ativos e pausados (fato)
     * @param plannedMonthlyTotal aportes planejados por mês dos ativos
     * @param activeCount         objetivos ativos
     * @param plannedIncomeShare  aportes planejados ÷ renda média (janela do Health), % inteiro; nulo sem renda
     * @param forecastCount       ativos com previsão (Estimado)
     * @param onTrackCount        dentre eles, os no prazo ou sem prazo
     * @param forecastFreshness   atualização do Forecast em que as previsões se apoiam
     */
    public record GoalListSummary(
            MoneyDto savedTotal,
            MoneyDto plannedMonthlyTotal,
            int activeCount,
            Integer plannedIncomeShare,
            int forecastCount,
            int onTrackCount,
            @Schema(allowableValues = {"UP_TO_DATE", "RECALCULATING", "NOT_BUILT"}) String forecastFreshness) {}

    public static GoalListResponse from(GoalListView view) {
        return new GoalListResponse(new GoalListSummary(GoalResponse.money(view.savedTotal()),
                GoalResponse.money(view.plannedMonthlyTotal()), view.activeCount(), view.plannedIncomeShare(),
                view.forecastCount(), view.onTrackCount(), view.forecastFreshness().name()),
                view.goals().stream().map(GoalResponse::from).toList());
    }
}
