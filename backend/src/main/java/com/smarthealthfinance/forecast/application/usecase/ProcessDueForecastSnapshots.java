package com.smarthealthfinance.forecast.application.usecase;

import com.smarthealthfinance.forecast.application.port.ForecastSnapshotStore;
import com.smarthealthfinance.forecast.application.service.ForecastSnapshotCalculator;
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
 * Recalcula os Workspaces vencidos (ADR-0031 §3), um por transação: pega um vencido com {@code SKIP LOCKED}
 * (outras instâncias pegam outros), recalcula e grava, e a gravação limpa a marca. Um evento que chega durante o
 * recálculo espera o lock e marca o Workspace de novo. Uma falha desfaz a transação e adia só aquele Workspace.
 */
@Service
public class ProcessDueForecastSnapshots {

    private static final Logger log = LoggerFactory.getLogger(ProcessDueForecastSnapshots.class);

    private final ForecastSnapshotStore store;
    private final ForecastSnapshotCalculator calculator;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final int batchSize;
    private final Duration failureBackoff;

    public ProcessDueForecastSnapshots(ForecastSnapshotStore store, ForecastSnapshotCalculator calculator,
                                     TransactionTemplate transactions, Clock clock,
                                     @Value("${shf.forecast.job.batch-size:50}") int batchSize,
                                     @Value("${shf.forecast.job.failure-backoff:60s}") Duration failureBackoff) {
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
            log.info("Forecast snapshot recalculated workspaceId={}", claimed.get());
            processed++;
        }
        return processed;
    }

    private void postpone(WorkspaceId workspace, RuntimeException failure) {
        log.error("Forecast snapshot failed workspaceId={} error={}", workspace, failure.getClass().getSimpleName());
        transactions.executeWithoutResult(status -> store.postpone(workspace,
                clock.instant().plus(failureBackoff)));
    }
}
