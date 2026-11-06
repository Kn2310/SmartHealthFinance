package com.smarthealthfinance.goals.application.service;

import com.smarthealthfinance.goals.application.exception.GoalNotFoundException;
import com.smarthealthfinance.goals.domain.model.Goal;
import com.smarthealthfinance.goals.domain.repository.GoalRepository;
import com.smarthealthfinance.goals.domain.valueobject.GoalId;
import com.smarthealthfinance.identity.application.service.CurrentUserService;
import com.smarthealthfinance.identity.application.service.WorkspaceAccessGuard;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Autorização dos casos de uso de objetivos: primeiro o Workspace (membership, ADR-0003), depois o objetivo, sempre
 * buscado dentro do Workspace autorizado.
 */
@Service
public class GoalAccess {

    private final CurrentUserService currentUser;
    private final WorkspaceAccessGuard accessGuard;
    private final GoalRepository goals;

    public GoalAccess(CurrentUserService currentUser, WorkspaceAccessGuard accessGuard, GoalRepository goals) {
        this.currentUser = currentUser;
        this.accessGuard = accessGuard;
        this.goals = goals;
    }

    /** @throws com.smarthealthfinance.identity.application.exception.WorkspaceNotFoundException sem membership */
    public WorkspaceId requireWorkspace(UUID workspaceId) {
        WorkspaceId id = new WorkspaceId(workspaceId);
        accessGuard.requireMember(currentUser.requireCurrentUserId(), id);
        return id;
    }

    /** @throws GoalNotFoundException se o objetivo não existir dentro do Workspace autorizado */
    public Goal requireGoal(WorkspaceId workspaceId, UUID goalId) {
        return goals.findById(workspaceId, new GoalId(goalId)).orElseThrow(GoalNotFoundException::new);
    }
}
