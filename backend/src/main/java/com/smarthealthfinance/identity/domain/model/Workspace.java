package com.smarthealthfinance.identity.domain.model;

import com.smarthealthfinance.identity.domain.enums.WorkspaceKind;
import com.smarthealthfinance.identity.domain.enums.WorkspaceRole;
import com.smarthealthfinance.identity.domain.valueobject.UserId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceName;

import java.time.Instant;
import java.util.*;

public final class Workspace {

    public static final Currency DEFAULT_BASE_CURRENCY = Currency.getInstance("BRL");

    private final WorkspaceId id;
    private final WorkspaceKind kind;
    private final UserId ownerId;
    private final WorkspaceName name;
    private final Currency baseCurrency;
    private final List<WorkspaceMembership> memberships;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final long version;

    private Workspace(
            WorkspaceId id,
            WorkspaceKind kind,
            UserId ownerId,
            WorkspaceName name,
            Currency baseCurrency,
            List<WorkspaceMembership> memberships,
            Instant createdAt,
            Instant updatedAt,
            long version
    ) {
        this.id = Objects.requireNonNull(id, "id");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        this.name = Objects.requireNonNull(name, "name");
        this.baseCurrency = Objects.requireNonNull(baseCurrency, "baseCurrency");
        this.memberships = List.copyOf(Objects.requireNonNull(memberships, "memberships"));
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.version = version;

        requireUniqueMembers(this.memberships);
        requireOwnerMembership(this.memberships, ownerId);
    }

    public static Workspace createPersonal(WorkspaceId id, UserId ownerId, Instant now) {
        return new Workspace(
                id, WorkspaceKind.PERSONAL, ownerId, WorkspaceName.PERSONAL_DEFAULT, DEFAULT_BASE_CURRENCY, List.of(new WorkspaceMembership(ownerId,WorkspaceRole.OWNER, now)), now, now, 0
        );
    }

    public static Workspace restore(
            WorkspaceId id,
            WorkspaceKind kind,
            UserId ownerId,
            WorkspaceName name,
            Currency baseCurrency,
            List<WorkspaceMembership> memberships,
            Instant createdAt,
            Instant updatedAt,
            long version
    ) {
        return new Workspace(id, kind, ownerId, name, baseCurrency, memberships, createdAt, updatedAt, version);
    }

    public boolean isMember(UserId userId) {
        return roleOf(userId).isPresent();
    }

    public Optional<WorkspaceRole> roleOf(UserId userId) {
        return memberships.stream()
                .filter(membership -> membership.userId().equals(userId))
                .map(WorkspaceMembership::role)
                .findFirst();
    }

    private static void requireUniqueMembers(List<WorkspaceMembership> memberships) {
        var seen = new HashSet<UserId>();

        for (WorkspaceMembership membership : memberships) {
            if (!seen.add(membership.userId())) {
                throw new IllegalArgumentException("Duplicate membership for user " + membership.userId());
            }
        }
    }

    private static void requireOwnerMembership(List<WorkspaceMembership> memberships, UserId ownerId) {
        boolean ownerIsOwner = memberships.stream()
                .anyMatch(m -> m.userId().equals(ownerId) && m.role() == WorkspaceRole.OWNER);

        if (!ownerIsOwner) {
            throw new IllegalArgumentException("Owner must have an OWNER membership");
        }
    }

    public WorkspaceId id() { return id; }
    public WorkspaceKind kind() { return kind; }
    public UserId ownerId() { return ownerId; }
    public WorkspaceName name() { return name; }
    public Currency baseCurrency() { return baseCurrency; }
    public List<WorkspaceMembership> memberships() { return memberships; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public long version() { return version; }
}
