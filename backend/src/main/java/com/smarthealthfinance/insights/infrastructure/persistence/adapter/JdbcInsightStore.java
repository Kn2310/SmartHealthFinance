package com.smarthealthfinance.insights.infrastructure.persistence.adapter;

import com.smarthealthfinance.health.domain.enums.Confidence;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.insights.application.port.InsightStore;
import com.smarthealthfinance.insights.application.usecase.MarkInsightsDirty;
import com.smarthealthfinance.insights.domain.enums.Importance;
import com.smarthealthfinance.insights.domain.enums.InsightDetector;
import com.smarthealthfinance.insights.domain.enums.InsightFeedback;
import com.smarthealthfinance.insights.domain.enums.InsightStatus;
import com.smarthealthfinance.insights.domain.enums.StatusReason;
import com.smarthealthfinance.insights.domain.model.Insight;
import com.smarthealthfinance.shared.domain.Money;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Insights e marcação com debounce (ADR-0022 §3/§5). Toda leitura e escrita é por Workspace. Os Insights conhecem o
 * evento que consomem só pelo nome.
 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcInsightStore implements InsightStore {

    /** Evento que o consumidor dos Insights processa (ADR-0022 §2). */
    static final String CONSUMED_EVENT = "health.snapshot-updated";

    /** Mesmo upsert de marca do Health: parâmetros workspace, agora, agora + quiet, maxWait em ms. */
    private static final String MARK = """
            insert into insights_workspaces as h (workspace_id, dirty_since, due_at) values (?, ?, ?)
            on conflict (workspace_id) do update set
              dirty_since = coalesce(h.dirty_since, excluded.dirty_since),
              due_at = least(excluded.due_at,
                             coalesce(h.dirty_since, excluded.dirty_since) + (? * interval '1 millisecond'))
            """;

    private static final String SELECT = """
            select i.id, i.workspace_id, i.fingerprint, i.algorithm_version, i.detector, i.period_key, i.subject,
                   i.status, i.status_reason, i.importance, i.impact, i.relevance, i.priority, i.confidence,
                   i.impact_amount, i.impact_points, i.partial, i.seen_at, i.dismissed_at, i.feedback, i.feedback_at,
                   i.revision, i.created_at, i.updated_at, i.status_changed_at, i.content::text as content,
                   w.base_currency
            from insights i join workspaces w on w.id = i.workspace_id
            """;

    private static final String UPSERT = """
            insert into insights (id, workspace_id, fingerprint, algorithm_version, detector, type, period_key,
                                  subject, status, status_reason, importance, impact, relevance, priority,
                                  confidence, impact_amount, impact_points, partial, seen_at, dismissed_at, feedback,
                                  feedback_at, revision, created_at, updated_at, status_changed_at, content)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            on conflict (workspace_id, fingerprint, algorithm_version) do update set
              status = excluded.status, status_reason = excluded.status_reason, importance = excluded.importance,
              impact = excluded.impact, relevance = excluded.relevance, priority = excluded.priority,
              confidence = excluded.confidence, impact_amount = excluded.impact_amount,
              impact_points = excluded.impact_points, partial = excluded.partial, seen_at = excluded.seen_at,
              dismissed_at = excluded.dismissed_at, feedback = excluded.feedback, feedback_at = excluded.feedback_at,
              revision = excluded.revision, updated_at = excluded.updated_at,
              status_changed_at = excluded.status_changed_at, content = excluded.content
            """;

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public JdbcInsightStore(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void markDirty(WorkspaceId workspaceId, Instant now, Duration quiet, Duration maxWait) {
        jdbc.update(MARK, workspaceId.value(), Timestamp.from(now), Timestamp.from(now.plus(quiet)),
                maxWait.toMillis());
    }

    @Override
    public int markForRebuild(String algorithmVersion, boolean all, Instant now) {
        Timestamp at = Timestamp.from(now);
        return jdbc.update("""
                        insert into insights_workspaces as h (workspace_id, dirty_since, due_at)
                        select w.id, ?::timestamptz, ?::timestamptz from workspaces w
                        where exists (select 1 from transactions t where t.workspace_id = w.id)
                          and (? or not exists (select 1 from insights_workspaces s
                                                where s.workspace_id = w.id and s.algorithm_version = ?))
                        on conflict (workspace_id) do update set
                          dirty_since = coalesce(h.dirty_since, excluded.dirty_since), due_at = excluded.due_at
                        """,
                at, at, all, algorithmVersion);
    }

    @Override
    public Optional<WorkspaceId> claimDue(Instant now) {
        return jdbc.query("""
                        select workspace_id from insights_workspaces
                        where due_at <= ?
                        order by due_at
                        limit 1
                        for update skip locked
                        """,
                (rs, row) -> new WorkspaceId(rs.getObject("workspace_id", UUID.class)), Timestamp.from(now))
                .stream().findFirst();
    }

    @Override
    public List<Insight> findAll(WorkspaceId workspaceId) {
        return jdbc.query(SELECT + " where i.workspace_id = ? order by i.created_at, i.id", this::insight,
                workspaceId.value());
    }

    @Override
    public void save(List<Insight> insights) {
        for (Insight i : insights) {
            jdbc.update(UPSERT, i.id(), i.workspaceId(), i.fingerprint(), i.algorithmVersion(), i.detector().name(),
                    i.type().name(), i.periodKey(), i.subject(), i.status().name(),
                    i.statusReason() == null ? null : i.statusReason().name(), i.importance().name(), i.impact(),
                    i.relevance(), i.priority(), i.confidence().name(),
                    i.impactAmount() == null ? null : i.impactAmount().amount(), i.impactPoints(), i.partial(),
                    timestamp(i.seenAt()), timestamp(i.dismissedAt()),
                    i.feedback() == null ? null : i.feedback().name(), timestamp(i.feedbackAt()), i.revision(),
                    timestamp(i.createdAt()), timestamp(i.updatedAt()), timestamp(i.statusChangedAt()),
                    json.writeValueAsString(InsightContentDocument.from(i.content())));
        }
    }

    @Override
    public void finishGeneration(WorkspaceId workspaceId, String algorithmVersion, Instant generatedAt,
                                 LocalDate asOf) {
        jdbc.update("""
                        insert into insights_workspaces as h (workspace_id, generated_at, as_of, algorithm_version)
                        values (?, ?, ?, ?)
                        on conflict (workspace_id) do update set dirty_since = null, due_at = null,
                          generated_at = excluded.generated_at, as_of = excluded.as_of,
                          algorithm_version = excluded.algorithm_version
                        """,
                workspaceId.value(), Timestamp.from(generatedAt), Date.valueOf(asOf), algorithmVersion);
    }

    @Override
    public void postpone(WorkspaceId workspaceId, Instant dueAt) {
        jdbc.update("update insights_workspaces set due_at = ? where workspace_id = ? and due_at is not null",
                Timestamp.from(dueAt), workspaceId.value());
    }

    @Override
    public Optional<Instant> oldestDirtySince() {
        Timestamp oldest = jdbc.queryForObject("select min(dirty_since) from insights_workspaces", Timestamp.class);
        return Optional.ofNullable(oldest).map(Timestamp::toInstant);
    }

    @Override
    public int purgeHiddenBefore(Instant before) {
        return jdbc.update("delete from insights where status <> 'ACTIVE' and updated_at < ?",
                Timestamp.from(before));
    }

    @Override
    public GenerationState state(WorkspaceId workspaceId, String algorithmVersion) {
        UUID workspace = workspaceId.value();
        return jdbc.queryForObject("""
                        select h.dirty_since is not null as dirty, h.generated_at, h.as_of,
                               coalesce(h.algorithm_version = ?, false) as built,
                               exists (select 1 from transactions t where t.workspace_id = ?) as has_transactions,
                               (select count(*) from outbox_events o
                                 where o.workspace_id = ? and o.event_type = ?
                                   and not exists (select 1 from processed_events e
                                                    where e.consumer = ? and e.event_id = o.id)) as pending
                        from (select 1) as one
                        left join insights_workspaces h on h.workspace_id = ?
                        """,
                (rs, row) -> {
                    Timestamp generatedAt = rs.getTimestamp("generated_at");
                    return new GenerationState(rs.getBoolean("dirty"), rs.getBoolean("built"),
                            generatedAt == null ? null : generatedAt.toInstant(),
                            rs.getObject("as_of", LocalDate.class), rs.getBoolean("has_transactions"),
                            rs.getInt("pending"));
                },
                algorithmVersion, workspace, workspace, CONSUMED_EVENT, MarkInsightsDirty.CONSUMER, workspace);
    }

    private Insight insight(ResultSet rs, int row) throws SQLException {
        Currency currency = Currency.getInstance(rs.getString("base_currency"));
        BigDecimal amount = rs.getBigDecimal("impact_amount");
        String reason = rs.getString("status_reason");
        String feedback = rs.getString("feedback");
        int points = rs.getInt("impact_points");
        Integer impactPoints = rs.wasNull() ? null : points;
        return new Insight(rs.getObject("id", UUID.class), rs.getObject("workspace_id", UUID.class),
                rs.getString("fingerprint"), rs.getString("algorithm_version"),
                InsightDetector.valueOf(rs.getString("detector")), rs.getString("period_key"),
                rs.getString("subject"), InsightStatus.valueOf(rs.getString("status")),
                reason == null ? null : StatusReason.valueOf(reason), Importance.valueOf(rs.getString("importance")),
                rs.getInt("impact"), rs.getInt("relevance"), Confidence.valueOf(rs.getString("confidence")),
                rs.getInt("priority"), amount == null ? null : new Money(amount, currency), impactPoints,
                rs.getBoolean("partial"),
                json.readValue(rs.getString("content"), InsightContentDocument.class).toContent(),
                instant(rs.getTimestamp("seen_at")), instant(rs.getTimestamp("dismissed_at")),
                feedback == null ? null : InsightFeedback.valueOf(feedback), instant(rs.getTimestamp("feedback_at")),
                rs.getInt("revision"), instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")),
                instant(rs.getTimestamp("status_changed_at")));
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
