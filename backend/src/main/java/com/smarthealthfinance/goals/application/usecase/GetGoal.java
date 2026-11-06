package com.smarthealthfinance.goals.application.usecase;

import com.smarthealthfinance.goals.application.dto.GoalView;
import com.smarthealthfinance.goals.application.service.GoalAccess;
import com.smarthealthfinance.goals.application.service.GoalReader;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Detalhe do objetivo com o progresso (fato) lido do destino (ADR-0035). */
@Service
public class GetGoal {

    private final GoalAccess access;
    private final GoalReader reader;

    public GetGoal(GoalAccess access, GoalReader reader) {
        this.access = access;
        this.reader = reader;
    }

    @Transactional(readOnly = true)
    public GoalView execute(UUID workspaceId, UUID goalId) {
        WorkspaceId workspace = access.requireWorkspace(workspaceId);
        return reader.view(workspace, access.requireGoal(workspace, goalId));
    }
}
