package com.smarthealthfinance.health.application.usecase;

import com.smarthealthfinance.health.application.port.HealthSnapshotStore;
import com.smarthealthfinance.health.application.service.HealthSnapshotCalculator;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Recalcula os Workspaces vencidos (ADR-0018 §3), um por transação: pega um vencido com {@code SKIP LOCKED}
 * (outras instâncias pegam outros), recalcula e grava, e a gravação limpa a marca. Um evento que chega durante o
 * recálculo espera o lock e marca o Workspace de novo. Uma falha desfaz a transação e adia só aquele Workspace.
 */
@Service
public class ProcessDueHealthSnapshots {

    private static final Logger log = LoggerFactory.getLogger(ProcessDueHealthSnapshots.class);

    private final HealthSnapshotStore store;
    private final HealthSnapshotCalculator calculator;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final int batchSize;
    private final Duration failureBackoff;

    public ProcessDueHealthSnapshots(HealthSnapshotStore store, HealthSnapshotCalculator calculator,
                                     TransactionTemplate transactions, Clock clock,
                                     @Value("${shf.health.job.batch-size:50}") int batchSize,
                                     @Value("${shf.health.job.failure-backoff:60s}") Duration failureBackoff) {
        this.store = store;
        this.calculator = calculator;
        this.transactions = transactions;
        this.clock = clock;
        this.batchSize = batchSize;
        this.failureBackoff = failureBackoff;
    }

    /** @return quantos Workspaces foram pegos (recalculados ou adiados por falha) */
    public int execute() {
        int processed = 0;
        while (processed < batchSize) {
            AtomicReference<WorkspaceId> claimed = new AtomicReference<>();
            try {
                transactions.executeWithoutResult(status -> {
                    Optional<WorkspaceId> due = store.claimDue(clock.instant());
                    due.ifPresent(workspace -> {
                        claimed.set(workspace);
                        calculator.recalculate(workspace);
                    });
                });
            }
            catch (RuntimeException failure) {
                if (claimed.get() == null) {
                    throw failure;
                }
                postpone(claimed.get(), failure);
                processed++;
                continue;
            }
            if (claimed.get() == null) {
                break;
            }
            log.info("Health snapshot recalculated workspaceId={}", claimed.get());
            processed++;
        }
        return processed;
    }

    private void postpone(WorkspaceId workspace, RuntimeException failure) {
        log.error("Health snapshot failed workspaceId={} error={}", workspace, failure.getClass().getSimpleName());
        transactions.executeWithoutResult(status -> store.postpone(workspace,
                clock.instant().plus(failureBackoff)));
    }
}
