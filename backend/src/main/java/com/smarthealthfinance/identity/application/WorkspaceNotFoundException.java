package com.smarthealthfinance.identity.application;

/**
 * Workspace inexistente OU sem membership do usuário — indistinguíveis de propósito (ADR-0003):
 * a API nunca revela a existência de Workspaces de outros usuários.
 */
public final class WorkspaceNotFoundException extends RuntimeException {
    public WorkspaceNotFoundException() {
        super("Workspace not found");
    }
}
