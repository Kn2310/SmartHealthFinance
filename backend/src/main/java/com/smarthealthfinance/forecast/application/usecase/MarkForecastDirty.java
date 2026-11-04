package com.smarthealthfinance.forecast.application.usecase;

import com.smarthealthfinance.forecast.application.port.ForecastSnapshotStore;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.application.port.ProcessedEvents;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumidor {@code forecast.snapshot} (ADR-0031 §2, §3), chamado para cada evento da fila do Forecast
 * ({@code analytics.projection-updated}, {@code account.changed}, {@code card.*}, {@code invoice.closed}): não calcula, só marca o Workspace para o job, com debounce. A marca e a inbox commitam
 * juntas; uma reentrega não tem efeito.
 */
@Service
public class MarkForecastDirty {

    public static final String CONSUMER = "forecast.snapshot";

    private static final Logger log = LoggerFactory.getLogger(MarkForecastDirty.class);

    private final ProcessedEvents processedEvents;
    private final ForecastSnapshotStore store;
    private final Clock clock;
    private final Duration quiet;
    private final Duration maxWait;
    private final MeterRegistry meters;

    public MarkForecastDirty(ProcessedEvents processedEvents, ForecastSnapshotStore store, Clock clock,
                           @Value("${shf.forecast.debounce.quiet:10s}") Duration quiet,
                           @Value("${shf.forecast.debounce.max-wait:60s}") Duration maxWait, MeterRegistry meters) {
        this.processedEvents = processedEvents;
        this.store = store;
        this.clock = clock;
        this.quiet = quiet;
        this.maxWait = maxWait;
        this.meters = meters;
    }

    /** @return false se o evento já tinha sido processado */
    @Transactional
    public boolean execute(UUID eventId, WorkspaceId workspaceId) {
        if (!processedEvents.markProcessed(CONSUMER, eventId)) {
            log.info("Forecast event already processed eventId={} workspaceId={}", eventId, workspaceId);
            return false;
        }
        store.markDirty(workspaceId, clock.instant(), quiet, maxWait);
        meters.counter("shf.forecast.snapshot.marked").increment();
        log.info("Forecast snapshot marked workspaceId={} eventId={}", workspaceId, eventId);
        return true;
    }
}
