package com.smarthealthfinance.shared.infrastructure.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Publica os eventos pendentes da outbox no RabbitMQ (ADR-0009 §14).
 * <p>
 * Lê em lotes com {@code FOR UPDATE SKIP LOCKED} (várias instâncias da API não publicam o mesmo lote ao mesmo
 * tempo), publica com publisher confirms e só então marca {@code published_at}. Falha do broker deixa os eventos
 * pendentes para a próxima rodada: at-least-once, nunca perda.
 */
@Component
@ConditionalOnProperty(prefix = "shf.outbox.relay", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    static final int BATCH_SIZE = 100;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final RabbitTemplate rabbit;
    private final JsonMapper json;
    private final Clock clock;
    private final Duration confirmTimeout;

    public OutboxRelay(JdbcTemplate jdbc, TransactionTemplate transactions, RabbitTemplate rabbit, JsonMapper json,
                       Clock clock, @Value("${shf.outbox.relay.confirm-timeout:5s}") Duration confirmTimeout) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.rabbit = rabbit;
        this.json = json;
        this.clock = clock;
        this.confirmTimeout = confirmTimeout;
    }

    @Scheduled(fixedDelayString = "${shf.outbox.relay.interval-ms:1000}")
    public void relay() {
        Integer published;
        do {
            published = transactions.execute(status -> publishBatch());
        }
        while (published != null && published == BATCH_SIZE);
    }

    /** @return quantos eventos foram publicados (0 se não havia pendentes ou se o broker falhou) */
    int publishBatch() {
        List<EventEnvelope> pending = jdbc.query("""
                        select id, event_type, event_version, occurred_at, workspace_id, aggregate_type, aggregate_id,
                               correlation_id, causation_id, trace_id, payload
                        from outbox_events
                        where published_at is null
                        order by occurred_at, id
                        limit ?
                        for update skip locked
                        """,
                envelopeAt(clock.instant()), BATCH_SIZE);

        if (pending.isEmpty()) {
            return 0;
        }

        try {
            rabbit.invoke(operations -> {
                for (EventEnvelope event : pending) {
                    operations.send(MessagingConfig.EVENTS_EXCHANGE, event.eventType(), toMessage(event));
                }
                operations.waitForConfirmsOrDie(confirmTimeout.toMillis());
                return null;
            });
        }
        catch (RuntimeException failure) {
            jdbc.batchUpdate("update outbox_events set attempts = attempts + 1, last_error = ? where id = ?",
                    pending, pending.size(), (ps, event) -> {
                        ps.setString(1, failure.getClass().getSimpleName());
                        ps.setObject(2, event.eventId());
                    });
            log.warn("Outbox relay could not publish events={} error={}", pending.size(),
                    failure.getClass().getSimpleName());
            return 0;
        }

        Timestamp publishedAt = Timestamp.from(pending.getFirst().publishedAt());
        jdbc.batchUpdate("""
                        update outbox_events set published_at = ?, attempts = attempts + 1, last_error = null
                        where id = ?
                        """,
                pending, pending.size(), (ps, event) -> {
                    ps.setTimestamp(1, publishedAt);
                    ps.setObject(2, event.eventId());
                });
        log.debug("Outbox relay published events={}", pending.size());
        return pending.size();
    }

    private Message toMessage(EventEnvelope event) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding("UTF-8");
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setMessageId(event.eventId().toString());
        properties.setType(event.eventType());
        properties.setCorrelationId(event.correlationId());
        return new Message(event.toJson(json), properties);
    }

    private RowMapper<EventEnvelope> envelopeAt(Instant publishedAt) {
        return (rs, row) -> new EventEnvelope(
                rs.getObject("id", UUID.class),
                rs.getString("event_type"),
                rs.getInt("event_version"),
                rs.getTimestamp("occurred_at").toInstant(),
                publishedAt,
                rs.getObject("workspace_id", UUID.class),
                rs.getString("aggregate_type"),
                rs.getObject("aggregate_id", UUID.class),
                rs.getString("correlation_id"),
                rs.getObject("causation_id", UUID.class),
                rs.getString("trace_id"),
                json.readTree(rs.getString("payload")));
    }
}
