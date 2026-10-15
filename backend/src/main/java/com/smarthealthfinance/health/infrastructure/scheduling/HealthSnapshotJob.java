package com.smarthealthfinance.health.infrastructure.scheduling;

import com.smarthealthfinance.health.application.port.HealthSnapshotStore;
import com.smarthealthfinance.health.application.usecase.MarkAllHealthDirty;
import com.smarthealthfinance.health.application.usecase.ProcessDueHealthSnapshots;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Jobs do Health (ADR-0018 §3/§5/§7):
 * <ul>
 *   <li>a cada {@code shf.health.job.interval-ms} (5 s): recalcula os Workspaces vencidos e atualiza a métrica
 *   {@code shf.health.snapshot.freshness} (segundos desde o {@code dirty_since} mais antigo; 0 sem pendência);</li>
 *   <li>às 00:05 do fuso de negócio ({@code shf.health.daily.cron}): marca todos os Workspaces com snapshot, porque o
 *   calendário muda o resultado sem nenhum evento.</li>
 * </ul>
 * Várias instâncias podem rodar juntas: o {@code SKIP LOCKED} reparte os Workspaces.
 */
@Component
public class HealthSnapshotJob {

    private static final Logger log = LoggerFactory.getLogger(HealthSnapshotJob.class);

    private final ProcessDueHealthSnapshots due;
    private final MarkAllHealthDirty daily;
    private final HealthSnapshotStore store;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final AtomicLong freshnessSeconds = new AtomicLong();

    public HealthSnapshotJob(ProcessDueHealthSnapshots due, MarkAllHealthDirty daily, HealthSnapshotStore store,
                             TransactionTemplate transactions, Clock clock, MeterRegistry meters) {
        this.due = due;
        this.daily = daily;
        this.store = store;
        this.transactions = transactions;
        this.clock = clock;
        Gauge.builder("shf.health.snapshot.freshness", freshnessSeconds, AtomicLong::get)
                .description("Segundos desde a marcação mais antiga ainda não recalculada")
                .baseUnit("seconds")
                .register(meters);
    }

    @Scheduled(fixedDelayString = "${shf.health.job.interval-ms:5000}")
    public void recalculateDue() {
        try {
            due.execute();
        }
        finally {
            updateFreshness();
        }
    }

    @Scheduled(cron = "${shf.health.daily.cron:0 5 0 * * *}", zone = "${shf.overview.zone:America/Sao_Paulo}")
    public void dailyRefresh() {
        try {
            daily.execute();
        }
        catch (RuntimeException failure) {
            log.error("Health daily refresh failed error={}", failure.getClass().getSimpleName());
        }
    }

    private void updateFreshness() {
        try {
            freshnessSeconds.set(transactions.execute(status -> store.oldestDirtySince()
                    .map(oldest -> Math.max(0, Duration.between(oldest, clock.instant()).toSeconds()))
                    .orElse(0L)));
        }
        catch (RuntimeException failure) {
            log.warn("Health freshness metric not updated error={}", failure.getClass().getSimpleName());
        }
    }
}
