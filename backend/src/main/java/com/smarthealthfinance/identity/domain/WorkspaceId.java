package com.smarthealthfinance.identity.domain;

import com.smarthealthfinance.shared.domain.UuidV7;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record WorkspaceId(
        UUID value
) {
    public WorkspaceId {
        Objects.requireNonNull(value, "value");
    }

    public static WorkspaceId generate(Instant now) {
        return new WorkspaceId(UuidV7.generate(now));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
