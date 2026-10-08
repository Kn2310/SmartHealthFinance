package com.smarthealthfinance.shared.infrastructure.messaging;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Saúde e retenção da outbox/inbox (spec 05.9: backlog e lag).
 * <p>
 * Expõe {@code shf.outbox.pending} e {@code shf.outbox.oldest.pending.age} (segundos) a partir de uma leitura
 * periódica, para que o scrape de métricas nunca consulte o banco. Remove eventos publicados e marcas de inbox
 * mais antigos que a retenção: a inbox precisa durar mais que qualquer reentrega possível.
 */
@Component
public class OutboxMaintenance {

    private static final Logger log = LoggerFactory.getLogger(OutboxMaintenance.class);

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Duration publishedRetention;
    private final Duration processedRetention;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong oldestPendingAgeSeconds = new AtomicLong();

    public OutboxMaintenance(JdbcTemplate jdbc, Clock clock, MeterRegistry meters,
                             @Value("${shf.outbox.published-retention:7d}") Duration publishedRetention,
                             @Value("${shf.outbox.processed-retention:30d}") Duration processedRetention) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.publishedRetention = publishedRetention;
        this.processedRetention = processedRetention;
        Gauge.builder("shf.outbox.pending", pending, AtomicLong::get)
                .description("Eventos da outbox ainda não publicados")
                .register(meters);
        Gauge.builder("shf.outbox.oldest.pending.age", oldestPendingAgeSeconds, AtomicLong::get)
                .description("Idade do evento pendente mais antigo")
                .baseUnit("seconds")
                .register(meters);
    }

    @Scheduled(fixedDelayString = "${shf.outbox.metrics.interval-ms:15000}")
    public void refreshMetrics() {
        jdbc.query("select count(*), min(occurred_at) from outbox_events where published_at is null", rs -> {
            pending.set(rs.getLong(1));
            Timestamp oldest = rs.getTimestamp(2);
            oldestPendingAgeSeconds.set(oldest == null ? 0
                    : Math.max(0, Duration.between(oldest.toInstant(), clock.instant()).toSeconds()));
        });
    }

    @Scheduled(cron = "${shf.outbox.cleanup.cron:0 17 * * * *}")
    public void cleanUp() {
        Instant now = clock.instant();
        int events = jdbc.update("delete from outbox_events where published_at < ?",
                Timestamp.from(now.minus(publishedRetention)));
        int processed = jdbc.update("delete from processed_events where processed_at < ?",
                Timestamp.from(now.minus(processedRetention)));
        if (events > 0 || processed > 0) {
            log.info("Outbox cleanup publishedEvents={} processedEvents={}", events, processed);
        }
    }
}
