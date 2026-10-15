package com.smarthealthfinance.health.application.usecase;

import com.smarthealthfinance.health.application.port.HealthSnapshotStore;
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
 * Consumidor {@code health.snapshot} (ADR-0018 §2/§3), chamado para cada {@code analytics.projection-updated} e
 * {@code account.changed}: não calcula, só marca o Workspace para o job, com debounce. A marca e a inbox commitam
 * juntas; uma reentrega não tem efeito.
 */
@Service
public class MarkHealthDirty {

    public static final String CONSUMER = "health.snapshot";

    private static final Logger log = LoggerFactory.getLogger(MarkHealthDirty.class);

    private final ProcessedEvents processedEvents;
    private final HealthSnapshotStore store;
    private final Clock clock;
    private final Duration quiet;
    private final Duration maxWait;
    private final MeterRegistry meters;

    public MarkHealthDirty(ProcessedEvents processedEvents, HealthSnapshotStore store, Clock clock,
                           @Value("${shf.health.debounce.quiet:10s}") Duration quiet,
                           @Value("${shf.health.debounce.max-wait:60s}") Duration maxWait, MeterRegistry meters) {
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
            log.info("Health event already processed eventId={} workspaceId={}", eventId, workspaceId);
            return false;
        }
        store.markDirty(workspaceId, clock.instant(), quiet, maxWait);
        meters.counter("shf.health.snapshot.marked").increment();
        log.info("Health snapshot marked workspaceId={} eventId={}", workspaceId, eventId);
        return true;
    }
}
