package com.smarthealthfinance.identity.infrastructure.persistence.entity;

import com.smarthealthfinance.identity.domain.enums.WorkspaceKind;
import com.smarthealthfinance.identity.domain.model.Workspace;
import com.smarthealthfinance.identity.domain.valueobject.UserId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceName;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

/**
 * Somente leitura por enquanto: inserções passam por {@link WorkspaceJpaRepository#insertPersonalIfAbsent}
 * (ON CONFLICT). Quando o agregado ganhar mutações, adicionar {@code from(Workspace)} como em UserJpaEntity.
 */
@Entity
@Table(name = "workspaces")
public class WorkspaceJpaEntity {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private WorkspaceKind kind;

    @Column(name = "owner_user_id", nullable = false, updatable = false)
    private UUID ownerUserId;

    @Column(nullable = false)
    private String name;

    @Column(name = "base_currency", nullable = false, updatable = false)
    private String baseCurrency;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "workspace_memberships", joinColumns = @JoinColumn(name = "workspace_id"))
    private List<WorkspaceMembershipEmbeddable> memberships = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected WorkspaceJpaEntity() {
    }

    public Workspace toDomain() {
        return Workspace.restore(new WorkspaceId(id), kind, new UserId(ownerUserId), new WorkspaceName(name),
                Currency.getInstance(baseCurrency),
                memberships.stream().map(WorkspaceMembershipEmbeddable::toDomain).toList(),
                createdAt, updatedAt, version);
    }
}
