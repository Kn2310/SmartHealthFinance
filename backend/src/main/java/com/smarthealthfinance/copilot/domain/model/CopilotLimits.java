package com.smarthealthfinance.copilot.domain.model;

import java.time.Duration;
import java.time.ZoneId;
import java.util.Objects;

/**
 * Limites provisórios até o M12 (ADR-0039 §10): quotas por Workspace no fuso de negócio e o token bucket por pessoa.
 * {@code enabled=false} desliga o Copilot sem afetar o resto do produto (runbook).
 */
public record CopilotLimits(boolean enabled, ZoneId zone, int dailyQuestions, int monthlyQuestions,
                            long monthlyInputTokens, int bucketCapacity, Duration refillInterval) {

    public CopilotLimits {
        Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(refillInterval, "refillInterval");
        if (dailyQuestions <= 0 || monthlyQuestions <= 0 || monthlyInputTokens <= 0 || bucketCapacity <= 0
                || refillInterval.isNegative() || refillInterval.isZero()) {
            throw new IllegalArgumentException("copilot limits must be positive");
        }
    }
}
