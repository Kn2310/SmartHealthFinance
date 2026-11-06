package com.smarthealthfinance.goals.presentation.dto.response;

import com.smarthealthfinance.goals.domain.enums.GoalForecastFreshness;
import com.smarthealthfinance.goals.domain.valueobject.GoalForecast;
import io.swagger.v3.oas.annotations.media.Schema;
import com.smarthealthfinance.shared.presentation.money.MoneyDto;

/**
 * Previsão de conclusão, sempre {@code ESTIMATED} (ADR-0034 §4, ADR-0036). Em {@code NO_FORECAST} só há
 * {@code reason}; em {@code REACHED}, nada além do status.
 *
 * @param completion  {@code "2027-08"}: o central, pelo plano
 * @param range       otimista = central; pessimista pelo ritmo real ({@code openEnded}: sem conclusão nesse ritmo)
 * @param realPace    mediana do aporte líquido dos meses fechados desde o início (até 3)
 * @param freshness   atualização do Forecast em que a previsão se apoia
 */
public record GoalForecastDto(
        @Schema(allowableValues = {"ESTIMATED"}) String basis,
        @Schema(allowableValues = {"ON_TRACK", "LATE", "NO_DEADLINE", "REACHED", "NO_FORECAST"}) String status,
        @Schema(allowableValues = {"FORECAST_NOT_READY", "NO_PLAN", "NOT_ACTIVE", "TOO_FAR"}) String reason,
        String completion,
        Integer contributionsLeft,
        Integer monthsLate,
        Integer monthsEarly,
        GoalForecastRange range,
        @Schema(allowableValues = {"HIGH", "MEDIUM", "LOW"}) String confidence,
        MoneyDto realPace,
        @Schema(allowableValues = {"UP_TO_DATE", "RECALCULATING", "NOT_BUILT"}) String freshness) {

    /** @param pessimistic nulo quando o ritmo real não chega ao alvo ({@code openEnded}) */
    public record GoalForecastRange(String optimistic, String pessimistic, boolean openEnded) {}

    public static GoalForecastDto from(GoalForecast forecast, GoalForecastFreshness freshness) {
        GoalForecastRange range = forecast.hasCompletion()
                ? new GoalForecastRange(forecast.completion().toString(),
                        forecast.pessimistic() == null ? null : forecast.pessimistic().toString(),
                        forecast.openEnded())
                : null;
        return new GoalForecastDto("ESTIMATED", forecast.status().name(),
                forecast.reason() == null ? null : forecast.reason().name(),
                forecast.hasCompletion() ? forecast.completion().toString() : null, forecast.contributionsLeft(),
                forecast.monthsLate(), forecast.monthsEarly(), range,
                forecast.confidence() == null ? null : forecast.confidence().name(),
                GoalResponse.money(forecast.realPace()), freshness == null ? null : freshness.name());
    }
}
