package com.smarthealthfinance.goals.presentation.dto.response;

import com.smarthealthfinance.goals.application.usecase.GetGoalProjection;
import com.smarthealthfinance.shared.presentation.money.MoneyDto;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Gráfico do D-GoalDetail (ADR-0036): guardado real (fato), projeção pelo plano (estimado) e prazo, em séries
 * separadas para nunca se misturarem.
 *
 * @param deadline {@code "2027-07"} ou nulo
 * @param forecast a mesma previsão do {@code GoalResponse}
 */
public record GoalProjectionResponse(MoneyDto target, String deadline, GoalProjectionSeries actual,
                                     GoalProjectionSeries projected, GoalForecastDto forecast) {

    /** @param points do mais antigo ao mais recente; {@code month} como {@code "2026-09"} */
    public record GoalProjectionSeries(@Schema(allowableValues = {"FACT", "ESTIMATED"}) String basis,
                                       List<GoalProjectionPoint> points) {}

    public record GoalProjectionPoint(String month, MoneyDto amount) {}

    public static GoalProjectionResponse from(GetGoalProjection.Projection projection) {
        return new GoalProjectionResponse(GoalResponse.money(projection.goal().targetAmount()),
                projection.goal().deadline() == null ? null : projection.goal().deadline().toString(),
                new GoalProjectionSeries("FACT", points(projection.actual())),
                new GoalProjectionSeries("ESTIMATED", points(projection.projected())),
                GoalForecastDto.from(projection.goal().forecast(), projection.goal().forecastFreshness()));
    }

    private static List<GoalProjectionPoint> points(List<GetGoalProjection.Point> points) {
        return points.stream()
                .map(p -> new GoalProjectionPoint(p.month().toString(), GoalResponse.money(p.amount())))
                .toList();
    }
}
