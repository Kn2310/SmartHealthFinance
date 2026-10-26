package com.smarthealthfinance.ingestion.application.service;

import com.smarthealthfinance.accounts.application.exception.AccountNotFoundException;
import com.smarthealthfinance.accounts.domain.model.Account;
import com.smarthealthfinance.accounts.domain.repository.AccountRepository;
import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.cards.application.usecase.RecordImportedCardTransactions;
import com.smarthealthfinance.identity.application.service.CurrentUserService;
import com.smarthealthfinance.identity.application.service.WorkspaceAccessGuard;
import com.smarthealthfinance.identity.domain.valueobject.UserId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.ingestion.application.exception.ImportNotFoundException;
import com.smarthealthfinance.ingestion.domain.model.ImportBatch;
import com.smarthealthfinance.ingestion.domain.repository.ImportBatchRepository;
import com.smarthealthfinance.ingestion.domain.valueobject.ImportBatchId;
import com.smarthealthfinance.ingestion.domain.valueobject.ImportDestination;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Autorização dos casos de uso de importação: primeiro o Workspace (membership, ADR-0003); depois batch e conta,
 * sempre buscados dentro do Workspace autorizado.
 */
@Service
public class ImportAccess {

    private final CurrentUserService currentUser;
    private final WorkspaceAccessGuard accessGuard;
    private final ImportBatchRepository batches;
    private final AccountRepository accounts;
    private final RecordImportedCardTransactions cardImports;

    public ImportAccess(CurrentUserService currentUser, WorkspaceAccessGuard accessGuard,
                        ImportBatchRepository batches, AccountRepository accounts,
                        RecordImportedCardTransactions cardImports) {
        this.currentUser = currentUser;
        this.accessGuard = accessGuard;
        this.batches = batches;
        this.accounts = accounts;
        this.cardImports = cardImports;
    }

    public record Actor(UserId userId, WorkspaceId workspaceId) {
    }

    /** @throws com.smarthealthfinance.identity.application.exception.WorkspaceNotFoundException sem membership */
    public Actor requireMember(UUID workspaceId) {
        UserId user = currentUser.requireCurrentUserId();
        WorkspaceId workspace = new WorkspaceId(workspaceId);
        accessGuard.requireMember(user, workspace);
        return new Actor(user, workspace);
    }

    public WorkspaceId requireWorkspace(UUID workspaceId) {
        return requireMember(workspaceId).workspaceId();
    }

    /** @throws ImportNotFoundException se o batch não existir dentro do Workspace autorizado */
    public ImportBatch requireBatch(WorkspaceId workspace, UUID importId) {
        return batches.findById(workspace, new ImportBatchId(importId)).orElseThrow(ImportNotFoundException::new);
    }

    /** Com lock da linha até o fim da transação. */
    public ImportBatch requireBatchForUpdate(WorkspaceId workspace, UUID importId) {
        return batches.findByIdForUpdate(workspace, new ImportBatchId(importId))
                .orElseThrow(ImportNotFoundException::new);
    }

    /**
     * @throws AccountNotFoundException conta inexistente ou de outro Workspace
     * @throws com.smarthealthfinance.accounts.domain.exception.AccountArchivedException conta arquivada
     */
    public Account requireActiveAccount(WorkspaceId workspace, AccountId accountId) {
        Account account = accounts.findById(workspace, accountId).orElseThrow(AccountNotFoundException::new);
        account.ensureActive();
        return account;
    }

    /**
     * A conta ou o cartão do batch aceita movimento (ADR-0009 §4 e §19). O cartão é verificado por Cards.
     *
     * @throws com.smarthealthfinance.cards.application.exception.CardNotFoundException cartão de outro Workspace
     * @throws com.smarthealthfinance.cards.domain.exception.CreditCardArchivedException cartão arquivado
     */
    public void requireAvailable(WorkspaceId workspace, ImportDestination destination) {
        if (destination.isCard()) {
            cardImports.requireImportable(workspace, destination.id());
        }
        else {
            requireActiveAccount(workspace, destination.requireAccount());
        }
    }
}
