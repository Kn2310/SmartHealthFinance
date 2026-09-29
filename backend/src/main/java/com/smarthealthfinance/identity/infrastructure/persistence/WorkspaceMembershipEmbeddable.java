package com.smarthealthfinance.identity.infrastructure.persistence;

import com.smarthealthfinance.identity.domain.UserId;
import com.smarthealthfinance.identity.domain.WorkspaceMembership;
import com.smarthealthfinance.identity.domain.WorkspaceRole;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Embeddable
public class WorkspaceMembershipEmbeddable {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WorkspaceRole role;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt;

    protected WorkspaceMembershipEmbeddable() {
    }

    WorkspaceMembership toDomain() {
        return new WorkspaceMembership(new UserId(userId), role, joinedAt);
    }
}
