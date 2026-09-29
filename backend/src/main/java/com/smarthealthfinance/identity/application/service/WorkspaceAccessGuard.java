package com.smarthealthfinance.identity.application.service;

import com.smarthealthfinance.identity.application.exception.WorkspaceNotFoundException;
import com.smarthealthfinance.identity.domain.model.Workspace;
import com.smarthealthfinance.identity.domain.repository.WorkspaceRepository;
import com.smarthealthfinance.identity.domain.valueobject.UserId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
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

    public Workspace loadAsMember(UserId userId, WorkspaceId workspaceId) {
        return workspaces.findById(workspaceId)
                .filter(workspace -> workspace.isMember(userId))
                .orElseThrow(WorkspaceNotFoundException::new);
    }
}
