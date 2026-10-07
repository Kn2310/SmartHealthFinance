package com.smarthealthfinance.transactions.application.service;

import com.smarthealthfinance.accounts.application.exception.AccountNotFoundException;
import com.smarthealthfinance.accounts.domain.model.Account;
import com.smarthealthfinance.accounts.domain.repository.AccountRepository;
import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.identity.application.service.CurrentUserService;
import com.smarthealthfinance.identity.application.service.WorkspaceAccessGuard;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.transactions.application.exception.TransactionNotFoundException;
import com.smarthealthfinance.transactions.domain.model.Transaction;
import com.smarthealthfinance.transactions.domain.repository.TransactionRepository;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionId;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Autorização dos casos de uso de transações: primeiro o Workspace (membership, ADR-0003); depois transação e
 * contas, sempre buscadas dentro do Workspace autorizado (ADR-0005).
 */
@Service
public class TransactionAccess {

    private final CurrentUserService currentUser;
    private final WorkspaceAccessGuard accessGuard;
    private final TransactionRepository transactions;
    private final AccountRepository accounts;

    public TransactionAccess(CurrentUserService currentUser, WorkspaceAccessGuard accessGuard,
                             TransactionRepository transactions, AccountRepository accounts) {
        this.currentUser = currentUser;
        this.accessGuard = accessGuard;
        this.transactions = transactions;
        this.accounts = accounts;
    }

    /** @throws com.smarthealthfinance.identity.application.exception.WorkspaceNotFoundException sem membership */
    public WorkspaceId requireWorkspace(UUID workspaceId) {
        WorkspaceId id = new WorkspaceId(workspaceId);
        accessGuard.requireMember(currentUser.requireCurrentUserId(), id);
        return id;
    }

    /** @throws TransactionNotFoundException se a transação não existir dentro do Workspace autorizado */
    public Transaction requireTransaction(UUID workspaceId, UUID transactionId) {
        return transactions.findById(requireWorkspace(workspaceId), new TransactionId(transactionId))
                .orElseThrow(TransactionNotFoundException::new);
    }

    /**
     * Conta apta a receber movimento. O Workspace já deve ter sido autorizado pelo chamador.
     *
     * @throws AccountNotFoundException conta inexistente ou de outro Workspace
     * @throws com.smarthealthfinance.accounts.domain.exception.AccountArchivedException conta arquivada
     */
    public Account requireActiveAccount(WorkspaceId workspaceId, AccountId accountId) {
        Account account = accounts.findById(workspaceId, accountId).orElseThrow(AccountNotFoundException::new);
        account.ensureActive();
        return account;
    }
}
