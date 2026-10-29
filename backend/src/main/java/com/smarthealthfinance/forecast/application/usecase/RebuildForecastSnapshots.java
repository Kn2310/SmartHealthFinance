package com.smarthealthfinance.forecast.application.usecase;

import com.smarthealthfinance.forecast.application.port.ForecastSnapshotStore;
import com.smarthealthfinance.forecast.application.service.ForecastSnapshotCalculator;
import com.smarthealthfinance.forecast.domain.service.ForecastCalculator;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reconstrução do snapshot do Forecast (ADR-0031 §8, runbook {@code forecast-rebuild.md}): a mesma função do recálculo,
 * por Workspace e sob o mesmo lock. Idempotente; cada Workspace roda na própria transação.
 */
@Service
public class RebuildForecastSnapshots {

    private static final Logger log = LoggerFactory.getLogger(RebuildForecastSnapshots.class);

    private final ForecastSnapshotStore store;
    private final ForecastSnapshotCalculator calculator;
    private final TransactionTemplate transactions;

    public RebuildForecastSnapshots(ForecastSnapshotStore store, ForecastSnapshotCalculator calculator,
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
                ForecastCalculator.ALGORITHM_VERSION, scope == Scope.ALL));
        if (targets == null || targets.isEmpty()) {
            return 0;
        }
        for (WorkspaceId workspace : targets) {
            execute(workspace);
        }
        log.info("Forecast snapshot rebuild finished scope={} workspaces={}", scope, targets.size());
        return targets.size();
    }

    public void execute(WorkspaceId workspaceId) {
        transactions.executeWithoutResult(status -> {
            store.lock(workspaceId);
            calculator.recalculate(workspaceId);
        });
        log.info("Forecast snapshot rebuilt workspaceId={}", workspaceId);
    }
}
