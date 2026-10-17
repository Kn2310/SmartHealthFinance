package com.smarthealthfinance.insights.presentation.dto.response;

import com.smarthealthfinance.insights.domain.model.InsightsFreshness;
import java.time.Instant;

/**
 * @param state            {@code UP_TO_DATE}, {@code UPDATING} ou {@code NOT_BUILT}
 * @param generatedAt      última geração dos insights; nula se nunca
 * @param healthComputedAt último recálculo do snapshot do Health que os insights leem; nulo se nunca
 */
public record InsightsStatusResponse(String state, Instant generatedAt, Instant healthComputedAt) {

    public static InsightsStatusResponse from(InsightsFreshness freshness) {
        return new InsightsStatusResponse(freshness.state().name(), freshness.generatedAt(),
                freshness.healthComputedAt());
    }
}
