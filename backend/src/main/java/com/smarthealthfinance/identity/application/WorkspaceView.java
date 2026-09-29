package com.smarthealthfinance.identity.application;

import com.smarthealthfinance.identity.domain.UserId;
import com.smarthealthfinance.identity.domain.Workspace;
import com.smarthealthfinance.identity.domain.WorkspaceKind;
import com.smarthealthfinance.identity.domain.WorkspaceRole;

import java.time.Instant;
import java.util.UUID;

/** Workspace visto por um membro: {@code role} é o papel de quem consulta. */
public record WorkspaceView(
        UUID id, String name, WorkspaceKind kind, String baseCurrency, WorkspaceRole role, Instant createdAt
) {
    static WorkspaceView from(Workspace workspace, UserId viewer) {
        return new WorkspaceView(
                workspace.id().value(), workspace.name().value(), workspace.kind(),
                workspace.baseCurrency().getCurrencyCode(), workspace.roleOf(viewer).orElseThrow(), workspace.createdAt()
        );
    }
}
