package com.smarthealthfinance.identity.infrastructure.persistence.entity;

import com.smarthealthfinance.identity.domain.enums.WorkspaceRole;
import com.smarthealthfinance.identity.domain.model.WorkspaceMembership;
import com.smarthealthfinance.identity.domain.valueobject.UserId;
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

    public WorkspaceMembership toDomain() {
        return new WorkspaceMembership(new UserId(userId), role, joinedAt);
    }
}
