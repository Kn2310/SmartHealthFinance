package com.smarthealthfinance.analytics.application.usecase;

import com.smarthealthfinance.analytics.application.port.AnalyticsProjectionStore;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.application.port.ProcessedEvents;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Consumidor da projeção de Analytics (ADR-0014 §3/§4), chamado para cada {@code transaction.*} e
 * {@code import.completed}.
 * <ol>
 *     <li>Inbox: uma reentrega do mesmo evento não tem efeito.</li>
 *     <li>Lock do Workspace: serializa recálculos e rebuild do mesmo Workspace.</li>
 *     <li>Projeção ausente ou de outra versão: rebuild completo (autocorreção).</li>
 *     <li>Senão, recalcula cada mês afetado a partir de {@code transactions}, exceto o mês já recalculado depois da
 *     publicação do evento (coalescência: o recálculo leu um estado que já incluía o fato).</li>
 * </ol>
 * Tudo numa transação: o efeito e a marca da inbox commitam juntos.
 */
@Service
public class RefreshAnalyticsProjection {

    public static final String CONSUMER = "analytics.projection";

    private static final Logger log = LoggerFactory.getLogger(RefreshAnalyticsProjection.class);

    private final ProcessedEvents processedEvents;
    private final AnalyticsProjectionStore store;
    private final Clock clock;
    private final Duration coalesceMargin;
    private final MeterRegistry meters;

    public RefreshAnalyticsProjection(ProcessedEvents processedEvents, AnalyticsProjectionStore store, Clock clock,
                                      @Value("${shf.analytics.coalesce-margin:2s}") Duration coalesceMargin,
                                      MeterRegistry meters) {
        this.processedEvents = processedEvents;
        this.store = store;
        this.clock = clock;
        this.coalesceMargin = coalesceMargin;
        this.meters = meters;
    }

    public enum Outcome { DUPLICATE, REBUILT, REFRESHED }

    /**
     * @param publishedAt quando o relay publicou o evento (o fato já estava commitado)
     * @param months      meses afetados; vazio só marca o evento como processado
     */
    @Transactional
    public Outcome execute(UUID eventId, Instant publishedAt, WorkspaceId workspaceId, Set<YearMonth> months) {
        Objects.requireNonNull(publishedAt, "publishedAt");
        Timer.Sample sample = Timer.start(meters);

        Outcome outcome = refresh(eventId, publishedAt, workspaceId, months);

        sample.stop(Timer.builder("shf.analytics.projection.refresh").tag("outcome", outcome.name())
                .register(meters));
        return outcome;
    }

    private Outcome refresh(UUID eventId, Instant publishedAt, WorkspaceId workspaceId, Set<YearMonth> months) {
        if (!processedEvents.markProcessed(CONSUMER, eventId)) {
            log.info("Analytics event already processed eventId={} workspaceId={}", eventId, workspaceId);
            return Outcome.DUPLICATE;
        }

        OptionalInt version = store.lockWorkspace(workspaceId);
        // Depois do lock: um recálculo concorrente do mesmo Workspace já commitou e está visível.
        Instant now = clock.instant();

        if (version.isEmpty() || version.getAsInt() != RebuildAnalyticsProjection.VERSION) {
            store.rebuild(workspaceId, RebuildAnalyticsProjection.VERSION, now);
            log.info("Analytics projection rebuilt workspaceId={} eventId={}", workspaceId, eventId);
            return Outcome.REBUILT;
        }

        int recomputed = 0;
        int coalesced = 0;
        for (YearMonth month : new TreeSet<>(months)) {
            boolean alreadyReflected = store.monthRefreshedAt(workspaceId, month)
                    .filter(refreshedAt -> refreshedAt.isAfter(publishedAt.plus(coalesceMargin)))
                    .isPresent();
            if (alreadyReflected) {
                coalesced++;
            }
            else {
                store.recomputeMonth(workspaceId, month, now);
                recomputed++;
            }
        }

        meters.counter("shf.analytics.projection.months", "result", "recomputed").increment(recomputed);
        meters.counter("shf.analytics.projection.months", "result", "coalesced").increment(coalesced);
        log.info("Analytics projection refreshed workspaceId={} eventId={} recomputed={} coalesced={}", workspaceId,
                eventId, recomputed, coalesced);
        return Outcome.REFRESHED;
    }
}
