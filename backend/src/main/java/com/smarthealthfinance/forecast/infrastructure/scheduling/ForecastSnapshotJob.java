package com.smarthealthfinance.forecast.infrastructure.scheduling;

import com.smarthealthfinance.forecast.application.port.ForecastSnapshotStore;
import com.smarthealthfinance.forecast.application.usecase.MarkAllForecastDirty;
import com.smarthealthfinance.forecast.application.usecase.ProcessDueForecastSnapshots;
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
 * Jobs do Forecast (ADR-0031 §3, §6, §9):
 * <ul>
 *   <li>a cada {@code shf.forecast.job.interval-ms} (5 s): recalcula os Workspaces vencidos e atualiza a métrica
 *   {@code shf.forecast.snapshot.freshness} (segundos desde o {@code dirty_since} mais antigo; 0 sem pendência);</li>
 *   <li>às 00:07 do fuso de negócio ({@code shf.forecast.daily.cron}): marca todos os Workspaces com snapshot, porque o
 *   calendário muda o resultado sem nenhum evento.</li>
 * </ul>
 * Várias instâncias podem rodar juntas: o {@code SKIP LOCKED} reparte os Workspaces.
 */
@Component
public class ForecastSnapshotJob {

    private static final Logger log = LoggerFactory.getLogger(ForecastSnapshotJob.class);

    private final ProcessDueForecastSnapshots due;
    private final MarkAllForecastDirty daily;
    private final ForecastSnapshotStore store;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final AtomicLong freshnessSeconds = new AtomicLong();

    public ForecastSnapshotJob(ProcessDueForecastSnapshots due, MarkAllForecastDirty daily, ForecastSnapshotStore store,
                             TransactionTemplate transactions, Clock clock, MeterRegistry meters) {
        this.due = due;
        this.daily = daily;
        this.store = store;
        this.transactions = transactions;
        this.clock = clock;
        Gauge.builder("shf.forecast.snapshot.freshness", freshnessSeconds, AtomicLong::get)
                .description("Segundos desde a marcação mais antiga ainda não recalculada")
                .baseUnit("seconds")
                .register(meters);
    }

    @Scheduled(fixedDelayString = "${shf.forecast.job.interval-ms:5000}")
    public void recalculateDue() {
        try {
            due.execute();
        }
        finally {
            updateFreshness();
        }
    }

    @Scheduled(cron = "${shf.forecast.daily.cron:0 7 0 * * *}", zone = "${shf.overview.zone:America/Sao_Paulo}")
    public void dailyRefresh() {
        try {
            daily.execute();
        }
        catch (RuntimeException failure) {
            log.error("Forecast daily refresh failed error={}", failure.getClass().getSimpleName());
        }
    }

    private void updateFreshness() {
        try {
            freshnessSeconds.set(transactions.execute(status -> store.oldestDirtySince()
                    .map(oldest -> Math.max(0, Duration.between(oldest, clock.instant()).toSeconds()))
                    .orElse(0L)));
        }
        catch (RuntimeException failure) {
            log.warn("Forecast freshness metric not updated error={}", failure.getClass().getSimpleName());
        }
    }
}
