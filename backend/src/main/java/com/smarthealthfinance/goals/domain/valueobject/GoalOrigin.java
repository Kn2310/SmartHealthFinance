package com.smarthealthfinance.goals.domain.valueobject;

import com.smarthealthfinance.goals.domain.enums.GoalOriginKind;
import com.smarthealthfinance.shared.domain.InvalidValueException;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Origem do objetivo (ADR-0034 §1): o {@code insightId} só existe (e é obrigatório) com {@code INSIGHT}. */
public record GoalOrigin(GoalOriginKind kind, UUID insightId) {

    public GoalOrigin {
        Objects.requireNonNull(kind, "kind");
        if (kind == GoalOriginKind.INSIGHT && insightId == null) {
            throw new InvalidValueException("origin.insightId", "REQUIRED");
        }
        if (kind != GoalOriginKind.INSIGHT && insightId != null) {
            throw new InvalidValueException("origin.insightId", "NOT_ALLOWED");
        }
    }

    public static GoalOrigin manual() {
        return new GoalOrigin(GoalOriginKind.MANUAL, null);
    }

    public Optional<UUID> insight() {
        return Optional.ofNullable(insightId);
    }
}
