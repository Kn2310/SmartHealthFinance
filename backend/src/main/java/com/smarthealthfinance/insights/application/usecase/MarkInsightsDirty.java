package com.smarthealthfinance.insights.application.usecase;

import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.insights.application.port.InsightStore;
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
 * Consumidor {@code insights.generation} (ADR-0022 §2/§3), chamado para cada {@code health.snapshot-updated}: não
 * gera, só marca o Workspace para o job, com debounce. A marca e a inbox commitam juntas; uma reentrega não tem efeito.
 */
@Service
public class MarkInsightsDirty {

    public static final String CONSUMER = "insights.generation";

    private static final Logger log = LoggerFactory.getLogger(MarkInsightsDirty.class);

    private final ProcessedEvents processedEvents;
    private final InsightStore store;
    private final Clock clock;
    private final Duration quiet;
    private final Duration maxWait;
    private final MeterRegistry meters;

    public MarkInsightsDirty(ProcessedEvents processedEvents, InsightStore store, Clock clock,
                             @Value("${shf.insights.debounce.quiet:10s}") Duration quiet,
                             @Value("${shf.insights.debounce.max-wait:60s}") Duration maxWait,
                             MeterRegistry meters) {
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
            log.info("Insights event already processed eventId={} workspaceId={}", eventId, workspaceId);
            return false;
        }
        store.markDirty(workspaceId, clock.instant(), quiet, maxWait);
        meters.counter("shf.insights.marked").increment();
        log.info("Insights generation marked workspaceId={} eventId={}", workspaceId, eventId);
        return true;
    }
}
