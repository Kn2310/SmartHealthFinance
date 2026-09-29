package com.smarthealthfinance.identity.domain;

import java.time.Instant;
import java.util.Objects;

public record WorkspaceMembership(
        UserId userId, WorkspaceRole role, Instant joinedAt
) {
    public WorkspaceMembership {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(joinedAt, "joinedAt");
    }
}
