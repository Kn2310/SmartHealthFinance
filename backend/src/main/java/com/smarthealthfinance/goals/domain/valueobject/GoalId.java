package com.smarthealthfinance.goals.domain.valueobject;

import com.smarthealthfinance.shared.domain.UuidV7;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record GoalId(UUID value) {

    public GoalId {
        Objects.requireNonNull(value, "value");
    }

    public static GoalId generate(Instant now) {
        return new GoalId(UuidV7.generate(now));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
