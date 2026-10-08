package com.smarthealthfinance.shared.infrastructure.messaging;

import com.smarthealthfinance.shared.application.event.IntegrationEvent;
import com.smarthealthfinance.shared.application.port.EventOutbox;
import com.smarthealthfinance.shared.domain.UuidV7;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** Grava o evento na tabela {@code outbox_events}, na transação do fato (ADR-0009 §14). */
@Repository
public class JdbcEventOutbox implements EventOutbox {

    /** Mesma chave usada pelo CorrelationIdFilter. */
    static final String CORRELATION_ID_MDC_KEY = "correlationId";

    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final Clock clock;

    public JdbcEventOutbox(JdbcTemplate jdbc, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID append(IntegrationEvent event) {
        Instant now = clock.instant();
        UUID eventId = UuidV7.generate(now);

        jdbc.update("""
                        insert into outbox_events
                          (id, event_type, event_version, workspace_id, aggregate_type, aggregate_id, correlation_id,
                           causation_id, trace_id, payload, occurred_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                        """,
                eventId, event.eventType(), event.eventVersion(), event.workspaceId(), event.aggregateType(),
                event.aggregateId(), MDC.get(CORRELATION_ID_MDC_KEY), event.causationId(), currentTraceId(),
                json.writeValueAsString(event.payload()), Timestamp.from(now));

        return eventId;
    }

    private static String currentTraceId() {
        SpanContext context = Span.current().getSpanContext();
        return context.isValid() ? context.getTraceId() : null;
    }
}
