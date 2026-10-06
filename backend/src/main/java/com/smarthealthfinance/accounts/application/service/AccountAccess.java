package com.smarthealthfinance.accounts.application.service;

import com.smarthealthfinance.accounts.application.exception.AccountNotFoundException;
import com.smarthealthfinance.accounts.domain.model.Account;
import com.smarthealthfinance.accounts.domain.repository.AccountRepository;
import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.identity.application.service.CurrentUserService;
import com.smarthealthfinance.identity.application.service.WorkspaceAccessGuard;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Autorização dos casos de uso de contas: primeiro o Workspace (membership, ADR-0003),
 * depois a conta — sempre buscada dentro do Workspace autorizado (ADR-0004).
 */
@Service
public class AccountAccess {

    private final CurrentUserService currentUser;
    private final WorkspaceAccessGuard accessGuard;
    private final AccountRepository accounts;

    public AccountAccess(CurrentUserService currentUser, WorkspaceAccessGuard accessGuard, AccountRepository accounts) {
        this.currentUser = currentUser;
        this.accessGuard = accessGuard;
        this.accounts = accounts;
    }

    /** @throws com.smarthealthfinance.identity.application.exception.WorkspaceNotFoundException sem membership */
    public WorkspaceId requireWorkspace(UUID workspaceId) {
        WorkspaceId id = new WorkspaceId(workspaceId);
        accessGuard.requireMember(currentUser.requireCurrentUserId(), id);
        return id;
    }

    /** @throws AccountNotFoundException se a conta não existir dentro do Workspace autorizado */
    public Account requireAccount(UUID workspaceId, UUID accountId) {
        return accounts.findById(requireWorkspace(workspaceId), new AccountId(accountId))
                .orElseThrow(AccountNotFoundException::new);
    }
}
