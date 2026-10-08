package com.smarthealthfinance.shared.infrastructure.messaging;

import com.smarthealthfinance.shared.application.port.ProcessedEvents;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;

/**
 * Inbox ({@code processed_events}). O {@code ON CONFLICT} espera o commit de uma entrega concorrente do mesmo
 * evento antes de decidir, então só uma delas produz efeito.
 */
@Repository
public class JdbcProcessedEvents implements ProcessedEvents {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public JdbcProcessedEvents(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markProcessed(String consumer, UUID eventId) {
        return jdbc.update("""
                        insert into processed_events (consumer, event_id, processed_at) values (?, ?, ?)
                        on conflict (consumer, event_id) do nothing
                        """,
                consumer, eventId, Timestamp.from(clock.instant())) == 1;
    }
}
