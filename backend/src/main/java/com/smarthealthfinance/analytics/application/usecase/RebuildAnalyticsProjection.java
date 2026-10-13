package com.smarthealthfinance.analytics.application.usecase;

import com.smarthealthfinance.analytics.application.port.AnalyticsProjectionStore;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;

/**
 * Reconstrução da projeção de Analytics (ADR-0014 §7): apaga e recalcula tudo a partir de {@code transactions}.
 * Idempotente e seguro de repetir; cada Workspace roda na própria transação, sob o mesmo lock dos recálculos.
 */
@Service
public class RebuildAnalyticsProjection {

    /** Versão do algoritmo da projeção. Mudou o que é gravado → nova versão → o runner reconstrói (modo missing). */
    public static final int VERSION = 1;

    private static final Logger log = LoggerFactory.getLogger(RebuildAnalyticsProjection.class);

    private final AnalyticsProjectionStore store;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public RebuildAnalyticsProjection(AnalyticsProjectionStore store, TransactionTemplate transactions, Clock clock) {
        this.store = store;
        this.transactions = transactions;
        this.clock = clock;
    }

    public enum Scope {
        /** Workspaces com transações e sem projeção na versão atual (backfill do deploy). */
        MISSING,
        /** Todos os Workspaces com transações (runbook, depois de replay da DLQ ou suspeita de divergência). */
        ALL
    }

    /** @return quantos Workspaces foram reconstruídos */
    public int execute(Scope scope) {
        List<WorkspaceId> targets = transactions.execute(status -> store.workspacesToRebuild(VERSION,
                scope == Scope.ALL));
        if (targets == null || targets.isEmpty()) {
            return 0;
        }
        for (WorkspaceId workspace : targets) {
            execute(workspace);
        }
        log.info("Analytics projection rebuild finished scope={} workspaces={}", scope, targets.size());
        return targets.size();
    }

    public void execute(WorkspaceId workspaceId) {
        transactions.executeWithoutResult(status -> {
            store.lockWorkspace(workspaceId);
            store.rebuild(workspaceId, VERSION, clock.instant());
        });
        log.info("Analytics projection rebuilt workspaceId={}", workspaceId);
    }
}
