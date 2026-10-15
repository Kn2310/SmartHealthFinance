package com.smarthealthfinance.health.application.usecase;

import com.smarthealthfinance.health.application.port.HealthSnapshotStore;
import com.smarthealthfinance.health.application.service.HealthSnapshotCalculator;
import com.smarthealthfinance.health.domain.service.HealthCalculator;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reconstrução do snapshot do Health (ADR-0018 §6, runbook {@code health-rebuild.md}): a mesma função do recálculo,
 * por Workspace e sob o mesmo lock. Idempotente; cada Workspace roda na própria transação.
 */
@Service
public class RebuildHealthSnapshots {

    private static final Logger log = LoggerFactory.getLogger(RebuildHealthSnapshots.class);

    private final HealthSnapshotStore store;
    private final HealthSnapshotCalculator calculator;
    private final TransactionTemplate transactions;

    public RebuildHealthSnapshots(HealthSnapshotStore store, HealthSnapshotCalculator calculator,
                                  TransactionTemplate transactions) {
        this.store = store;
        this.calculator = calculator;
        this.transactions = transactions;
    }

    public enum Scope {
        /** Workspaces com transações e sem snapshot na versão atual (deploy e nova versão do algoritmo). */
        MISSING,
        /** Todos os Workspaces com transações (runbook). */
        ALL
    }

    /** @return quantos Workspaces foram reconstruídos */
    public int execute(Scope scope) {
        List<WorkspaceId> targets = transactions.execute(status -> store.workspacesToRebuild(
                HealthCalculator.ALGORITHM_VERSION, scope == Scope.ALL));
        if (targets == null || targets.isEmpty()) {
            return 0;
        }
        for (WorkspaceId workspace : targets) {
            execute(workspace);
        }
        log.info("Health snapshot rebuild finished scope={} workspaces={}", scope, targets.size());
        return targets.size();
    }

    public void execute(WorkspaceId workspaceId) {
        transactions.executeWithoutResult(status -> {
            store.lock(workspaceId);
            calculator.recalculate(workspaceId);
        });
        log.info("Health snapshot rebuilt workspaceId={}", workspaceId);
    }
}
