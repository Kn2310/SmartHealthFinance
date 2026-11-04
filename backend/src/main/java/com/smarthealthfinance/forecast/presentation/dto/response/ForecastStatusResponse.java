package com.smarthealthfinance.forecast.presentation.dto.response;

import com.smarthealthfinance.forecast.domain.model.ForecastFreshness;

import java.time.Instant;
import java.time.LocalDate;

/**
 * @param state                {@code UP_TO_DATE}, {@code RECALCULATING} ou {@code NOT_BUILT}
 * @param computedAt           último recálculo do snapshot; nulo se nunca
 * @param asOf                 data-base (fuso de negócio) do snapshot atual; nula sem snapshot
 * @param analyticsRefreshedAt último recálculo da projeção de Analytics que o Forecast lê; nulo se nunca
 */
public record ForecastStatusResponse(String state, Instant computedAt, LocalDate asOf, Instant analyticsRefreshedAt) {

    public static ForecastStatusResponse from(ForecastFreshness freshness) {
        return new ForecastStatusResponse(freshness.state().name(), freshness.computedAt(), freshness.asOf(),
                freshness.analyticsRefreshedAt());
    }
}
