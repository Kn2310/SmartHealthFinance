package com.smarthealthfinance.identity.domain;

import java.util.List;
import java.util.Optional;

public interface WorkspaceRepository {

    Optional<Workspace> findById(WorkspaceId id);

    Optional<Workspace> findPersonalByOwner(UserId ownerId);

    /** Workspaces em que o usuário possui membership, em ordem de criação. */
    List<Workspace> findAllByMember(UserId userId);

    /**
     * Insere o Workspace pessoal e suas memberships se o dono ainda não tiver um.
     * Idempotente e seguro sob concorrência (garantido pelo banco).
     * @return false se outra requisição criou primeiro
     * @throws IllegalArgumentException se o Workspace não for PERSONAL
     */
    boolean addPersonalIfAbsent(Workspace workspace);
}
