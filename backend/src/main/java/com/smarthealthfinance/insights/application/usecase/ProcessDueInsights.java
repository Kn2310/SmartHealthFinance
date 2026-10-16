package com.smarthealthfinance.insights.application.usecase;

import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.insights.application.port.InsightStore;
import com.smarthealthfinance.insights.application.service.InsightGenerator;
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
 * Gera os Workspaces vencidos (ADR-0022 §3), um por transação: pega um vencido com {@code SKIP LOCKED} (outras
 * instâncias pegam outros), gera e grava, e a gravação limpa a marca. Um evento que chega durante a geração espera o
 * lock e marca o Workspace de novo. Uma falha desfaz a transação e adia só aquele Workspace.
 */
@Service
public class ProcessDueInsights {

    private static final Logger log = LoggerFactory.getLogger(ProcessDueInsights.class);

    private final InsightStore store;
    private final InsightGenerator generator;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final int batchSize;
    private final Duration failureBackoff;

    public ProcessDueInsights(InsightStore store, InsightGenerator generator, TransactionTemplate transactions,
                              Clock clock, @Value("${shf.insights.job.batch-size:50}") int batchSize,
                              @Value("${shf.insights.job.failure-backoff:60s}") Duration failureBackoff) {
        this.store = store;
        this.generator = generator;
        this.transactions = transactions;
        this.clock = clock;
        this.batchSize = batchSize;
        this.failureBackoff = failureBackoff;
    }

    /** @return quantos Workspaces foram pegos (gerados ou adiados por falha) */
    public int execute() {
        int processed = 0;
        while (processed < batchSize) {
            AtomicReference<WorkspaceId> claimed = new AtomicReference<>();
            AtomicReference<InsightGenerator.Outcome> outcome = new AtomicReference<>();
            try {
                transactions.executeWithoutResult(status -> {
                    Optional<WorkspaceId> due = store.claimDue(clock.instant());
                    due.ifPresent(workspace -> {
                        claimed.set(workspace);
                        outcome.set(generator.generate(workspace));
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
            log.info("Insights generated workspaceId={} detected={} created={} changed={}", claimed.get(),
                    outcome.get().detected(), outcome.get().created(), outcome.get().changed());
            processed++;
        }
        return processed;
    }

    private void postpone(WorkspaceId workspace, RuntimeException failure) {
        log.error("Insights generation failed workspaceId={} error={}", workspace,
                failure.getClass().getSimpleName());
        transactions.executeWithoutResult(status -> store.postpone(workspace, clock.instant().plus(failureBackoff)));
    }
}
