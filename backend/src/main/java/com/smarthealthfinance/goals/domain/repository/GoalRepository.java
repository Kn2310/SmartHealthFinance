package com.smarthealthfinance.goals.domain.repository;

import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.goals.domain.model.Goal;
import com.smarthealthfinance.goals.domain.valueobject.GoalId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;

import java.util.List;
import java.util.Optional;

/** Toda consulta é escopada pelo Workspace: objetivo de outro Workspace é indistinguível de inexistente. */
public interface GoalRepository {

    Optional<Goal> findById(WorkspaceId workspaceId, GoalId goalId);

    /** Mais recentes primeiro (createdAt desc, id desc). */
    List<Goal> findAllByWorkspace(WorkspaceId workspaceId);

    /** O objetivo ativo ou pausado que tem esta conta como destino, se houver (exclusividade, ADR-0034 §2.1). */
    Optional<Goal> findOpenByDestinationAccount(WorkspaceId workspaceId, AccountId accountId);

    /** A reserva de emergência ativa ou pausada do Workspace, se houver (ADR-0034 §3). */
    Optional<Goal> findOpenReserve(WorkspaceId workspaceId);

    void add(Goal goal);

    void save(Goal goal);
}
