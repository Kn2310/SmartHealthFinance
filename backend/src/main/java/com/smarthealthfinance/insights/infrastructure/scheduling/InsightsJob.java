package com.smarthealthfinance.insights.infrastructure.scheduling;

import com.smarthealthfinance.insights.application.port.InsightStore;
import com.smarthealthfinance.insights.application.usecase.ProcessDueInsights;
import com.smarthealthfinance.insights.application.usecase.PurgeInsights;
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
 * Jobs dos Insights (ADR-0022 §3/§6/§8):
 * <ul>
 *   <li>a cada {@code shf.insights.job.interval-ms} (5 s): gera os Workspaces vencidos e atualiza a métrica
 *   {@code shf.insights.generation.freshness} (segundos desde o {@code dirty_since} mais antigo; 0 sem pendência);</li>
 *   <li>às 00:20 do fuso de negócio ({@code shf.insights.purge.cron}): retenção de 13 meses.</li>
 * </ul>
 * O ciclo diário da geração vem do job diário do Health (00:05), que publica {@code health.snapshot-updated}.
 */
@Component
public class InsightsJob {

    private static final Logger log = LoggerFactory.getLogger(InsightsJob.class);

    private final ProcessDueInsights due;
    private final PurgeInsights purge;
    private final InsightStore store;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final AtomicLong freshnessSeconds = new AtomicLong();

    public InsightsJob(ProcessDueInsights due, PurgeInsights purge, InsightStore store,
                       TransactionTemplate transactions, Clock clock, MeterRegistry meters) {
        this.due = due;
        this.purge = purge;
        this.store = store;
        this.transactions = transactions;
        this.clock = clock;
        Gauge.builder("shf.insights.generation.freshness", freshnessSeconds, AtomicLong::get)
                .description("Segundos desde a marcação mais antiga ainda não gerada")
                .baseUnit("seconds")
                .register(meters);
    }

    @Scheduled(fixedDelayString = "${shf.insights.job.interval-ms:5000}")
    public void generateDue() {
        try {
            due.execute();
        }
        finally {
            updateFreshness();
        }
    }

    @Scheduled(cron = "${shf.insights.purge.cron:0 20 0 * * *}", zone = "${shf.overview.zone:America/Sao_Paulo}")
    public void purgeHidden() {
        try {
            purge.execute();
        }
        catch (RuntimeException failure) {
            log.error("Insights purge failed error={}", failure.getClass().getSimpleName());
        }
    }

    private void updateFreshness() {
        try {
            freshnessSeconds.set(transactions.execute(status -> store.oldestDirtySince()
                    .map(oldest -> Math.max(0, Duration.between(oldest, clock.instant()).toSeconds()))
                    .orElse(0L)));
        }
        catch (RuntimeException failure) {
            log.warn("Insights freshness metric not updated error={}", failure.getClass().getSimpleName());
        }
    }
}
