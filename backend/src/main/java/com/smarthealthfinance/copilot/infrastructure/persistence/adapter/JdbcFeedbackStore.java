package com.smarthealthfinance.copilot.infrastructure.persistence.adapter;

import com.smarthealthfinance.copilot.application.port.FeedbackStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * Feedback do Copilot em {@code copilot_feedback} (ADR-0040 §8, §10): sem conteúdo, só avaliação, motivo, intenção e
 * versões. O último envio vale; um {@code requestId} nunca troca de dono.
 */
@Repository
class JdbcFeedbackStore implements FeedbackStore {

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    JdbcFeedbackStore(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void upsert(Feedback feedback) {
        jdbc.update("""
                insert into copilot_feedback (request_id, workspace_id, user_id, rating, reason, intent, versions,
                                              created_at, updated_at, expires_at)
                values (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                on conflict (request_id) do update
                  set rating = excluded.rating, reason = excluded.reason, updated_at = excluded.updated_at
                  where copilot_feedback.workspace_id = excluded.workspace_id
                    and copilot_feedback.user_id = excluded.user_id
                """, feedback.requestId(), feedback.workspaceId().value(), feedback.userId().value(),
                feedback.rating().name(), feedback.reason() == null ? null : feedback.reason().name(),
                feedback.intent() == null ? null : feedback.intent().name(),
                json.writeValueAsString(feedback.versions()), Timestamp.from(feedback.now()),
                Timestamp.from(feedback.now()), Timestamp.from(feedback.expiresAt()));
    }

    @Override
    public int purgeExpired(Instant now, int batch) {
        return jdbc.update("""
                delete from copilot_feedback where request_id in (
                  select request_id from copilot_feedback where expires_at <= ?
                  order by expires_at limit ? for update skip locked)
                """, Timestamp.from(now), batch);
    }
}
