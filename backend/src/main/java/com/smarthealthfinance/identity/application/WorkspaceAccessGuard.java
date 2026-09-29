package com.smarthealthfinance.identity.application;

import com.smarthealthfinance.identity.domain.UserId;
import com.smarthealthfinance.identity.domain.Workspace;
import com.smarthealthfinance.identity.domain.WorkspaceId;
import com.smarthealthfinance.identity.domain.WorkspaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ponto único de autorização por Workspace (ADR-0003). Casos de uso de outros módulos
 * (accounts, transactions, ...) chamam {@link #requireMember} antes de ler ou escrever dados do Workspace.
 */
@Service
public class WorkspaceAccessGuard {

    private final WorkspaceRepository workspaces;

    public WorkspaceAccessGuard(WorkspaceRepository workspaces) {
        this.workspaces = workspaces;
    }

    /** @throws WorkspaceNotFoundException se o Workspace não existir ou o usuário não for membro */
    @Transactional(readOnly = true)
    public void requireMember(UserId userId, WorkspaceId workspaceId) {
        loadAsMember(userId, workspaceId);
    }

    Workspace loadAsMember(UserId userId, WorkspaceId workspaceId) {
        return workspaces.findById(workspaceId)
                .filter(workspace -> workspace.isMember(userId))
                .orElseThrow(WorkspaceNotFoundException::new);
    }
}
