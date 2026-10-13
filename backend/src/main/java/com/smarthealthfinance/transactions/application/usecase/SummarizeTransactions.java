package com.smarthealthfinance.transactions.application.usecase;

import com.smarthealthfinance.identity.application.service.CurrentUserService;
import com.smarthealthfinance.identity.application.service.WorkspaceAccessGuard;
import com.smarthealthfinance.identity.domain.model.Workspace;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.transactions.application.port.TransactionSummaryReader;
import com.smarthealthfinance.transactions.application.port.TransactionSummaryReader.TransactionSummary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Métricas do topo de D-Transactions (ADR-0016): Entradas, Saídas, Reembolsos, Transferências e Resultado do período
 * filtrado. Mesmos filtros e validações da listagem; autorização antes de validar.
 */
@Service
public class SummarizeTransactions {

    private final CurrentUserService currentUser;
    private final WorkspaceAccessGuard accessGuard;
    private final TransactionSummaryReader reader;

    public SummarizeTransactions(CurrentUserService currentUser, WorkspaceAccessGuard accessGuard,
                                 TransactionSummaryReader reader) {
        this.currentUser = currentUser;
        this.accessGuard = accessGuard;
        this.reader = reader;
    }

    /** {@code page}/{@code pageSize} da query são ignorados: o resumo é do período filtrado inteiro. */
    @Transactional(readOnly = true)
    public TransactionSummary execute(UUID workspaceId, ListTransactions.Query query) {
        Workspace workspace = accessGuard.loadAsMember(currentUser.requireCurrentUserId(), new WorkspaceId(workspaceId));
        return reader.summarize(workspace.id(), ListTransactions.toCriteria(query), workspace.baseCurrency());
    }
}
