package com.smarthealthfinance.goals.infrastructure.persistence.adapter;

import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.goals.domain.enums.GoalStatus;
import com.smarthealthfinance.goals.domain.enums.GoalType;
import com.smarthealthfinance.goals.domain.model.Goal;
import com.smarthealthfinance.goals.domain.repository.GoalRepository;
import com.smarthealthfinance.goals.domain.valueobject.GoalId;
import com.smarthealthfinance.goals.infrastructure.persistence.entity.GoalJpaEntity;
import com.smarthealthfinance.goals.infrastructure.persistence.repository.GoalJpaRepository;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class JpaGoalRepository implements GoalRepository {

    private static final List<GoalStatus> OPEN = List.of(GoalStatus.ACTIVE, GoalStatus.PAUSED);

    private final GoalJpaRepository jpa;

    public JpaGoalRepository(GoalJpaRepository jpa) {
        this.jpa = jpa;
    }

    /** Sempre filtrado pelo Workspace: objetivo de outro Workspace é indistinguível de inexistente. */
    @Override
    public Optional<Goal> findById(WorkspaceId workspaceId, GoalId goalId) {
        return jpa.findByIdAndWorkspaceId(goalId.value(), workspaceId.value()).map(GoalJpaEntity::toDomain);
    }

    @Override
    public List<Goal> findAllByWorkspace(WorkspaceId workspaceId) {
        return jpa.findAllByWorkspaceIdOrderByCreatedAtDescIdDesc(workspaceId.value()).stream()
                .map(GoalJpaEntity::toDomain)
                .toList();
    }

    @Override
    public Optional<Goal> findOpenByDestinationAccount(WorkspaceId workspaceId, AccountId accountId) {
        return jpa.findFirstByWorkspaceIdAndDestinationAccountIdAndStatusIn(workspaceId.value(), accountId.value(),
                OPEN).map(GoalJpaEntity::toDomain);
    }

    @Override
    public Optional<Goal> findOpenReserve(WorkspaceId workspaceId) {
        return jpa.findFirstByWorkspaceIdAndTypeAndStatusIn(workspaceId.value(), GoalType.EMERGENCY_RESERVE, OPEN)
                .map(GoalJpaEntity::toDomain);
    }

    @Override
    public void add(Goal goal) {
        jpa.save(GoalJpaEntity.from(goal));
    }

    /** O version vindo do domínio faz o merge falhar com OptimisticLockingFailureException se estiver obsoleto. */
    @Override
    public void save(Goal goal) {
        jpa.save(GoalJpaEntity.from(goal));
    }
}
