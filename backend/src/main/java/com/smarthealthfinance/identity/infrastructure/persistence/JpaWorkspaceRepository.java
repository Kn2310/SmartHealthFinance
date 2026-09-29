package com.smarthealthfinance.identity.infrastructure.persistence;

import com.smarthealthfinance.identity.domain.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public class JpaWorkspaceRepository implements WorkspaceRepository {

    private final WorkspaceJpaRepository jpa;

    public JpaWorkspaceRepository(WorkspaceJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<Workspace> findById(WorkspaceId id) {
        return jpa.findById(id.value()).map(WorkspaceJpaEntity::toDomain);
    }

    @Override
    public Optional<Workspace> findPersonalByOwner(UserId ownerId) {
        return jpa.findByKindAndOwnerUserId(WorkspaceKind.PERSONAL, ownerId.value()).map(WorkspaceJpaEntity::toDomain);
    }

    @Override
    public List<Workspace> findAllByMember(UserId userId) {
        return jpa.findAllByMember(userId.value()).stream().map(WorkspaceJpaEntity::toDomain).toList();
    }

    /**
     * MANDATORY: Workspace e memberships precisam ser gravados na mesma transação do caso de uso
     * (junto com o usuário, no provisionamento).
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean addPersonalIfAbsent(Workspace workspace) {
        if (workspace.kind() != WorkspaceKind.PERSONAL) {
            throw new IllegalArgumentException("addPersonalIfAbsent aceita apenas Workspaces PERSONAL");
        }

        int inserted = jpa.insertPersonalIfAbsent(workspace.id().value(), workspace.ownerId().value(),
                workspace.name().value(), workspace.baseCurrency().getCurrencyCode(),
                workspace.createdAt(), workspace.updatedAt());

        if (inserted == 0) {
            return false;
        }

        workspace.memberships().forEach(membership -> jpa.insertMembership(workspace.id().value(),
                membership.userId().value(), membership.role().name(), membership.joinedAt()));

        return true;
    }
}
