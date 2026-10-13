package com.smarthealthfinance.analytics.presentation.dto.response;

import com.smarthealthfinance.analytics.domain.model.ProjectionFreshness;

import java.time.Instant;

/**
 * @param state         {@code UP_TO_DATE}, {@code UPDATING} ou {@code NOT_BUILT}
 * @param refreshedAt   último recálculo da projeção; nulo se nunca construída
 * @param pendingEvents eventos do Workspace ainda não refletidos
 */
public record AnalyticsStatusResponse(String state, Instant refreshedAt, int pendingEvents) {

    public static AnalyticsStatusResponse from(ProjectionFreshness freshness) {
        return new AnalyticsStatusResponse(freshness.state().name(), freshness.refreshedAt(),
                freshness.pendingEvents());
    }
}
