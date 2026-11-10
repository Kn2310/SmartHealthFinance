package com.smarthealthfinance.copilot.application.dto;

import com.smarthealthfinance.copilot.domain.enums.AdmissionCode;
import com.smarthealthfinance.identity.domain.valueobject.UserId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Decisão de admissão (ADR-0039 §13). {@code retryAfter} só com {@code RATE_LIMITED} (vira {@code Retry-After} na API
 * da 8.2); {@code resetsAt} só com {@code QUOTA_EXCEEDED} (a data de liberação do estado "Limite").
 */
public record Admission(AdmissionCode code, WorkspaceId workspaceId, UserId userId, Duration retryAfter,
                        Instant resetsAt) {

    public Admission {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(userId, "userId");
    }

    public boolean admitted() {
        return code == AdmissionCode.ADMITTED;
    }

    public Optional<Duration> retryAfterValue() {
        return Optional.ofNullable(retryAfter);
    }

    public Optional<Instant> resetsAtValue() {
        return Optional.ofNullable(resetsAt);
    }
}
