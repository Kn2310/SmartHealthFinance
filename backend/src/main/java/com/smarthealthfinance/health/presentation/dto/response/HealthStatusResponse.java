package com.smarthealthfinance.health.presentation.dto.response;

import com.smarthealthfinance.health.domain.model.HealthFreshness;

import java.time.Instant;

/**
 * @param state                {@code UP_TO_DATE}, {@code RECALCULATING} ou {@code NOT_BUILT}
 * @param computedAt           último recálculo do snapshot; nulo se nunca
 * @param analyticsRefreshedAt último recálculo da projeção de Analytics que o Health lê; nulo se nunca
 */
public record HealthStatusResponse(String state, Instant computedAt, Instant analyticsRefreshedAt) {

    public static HealthStatusResponse from(HealthFreshness freshness) {
        return new HealthStatusResponse(freshness.state().name(), freshness.computedAt(),
                freshness.analyticsRefreshedAt());
    }
}
