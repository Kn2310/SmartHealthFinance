package com.smarthealthfinance.health.infrastructure.persistence.adapter;

import com.smarthealthfinance.health.application.port.HealthSnapshotStore;
import com.smarthealthfinance.health.application.usecase.MarkHealthDirty;
import com.smarthealthfinance.health.domain.model.HealthResult;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Snapshot do Health e marcação com debounce (ADR-0018 §3/§4). Toda leitura e escrita é por Workspace.
 * O Health conhece os eventos que consome só pelo nome.
 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcHealthSnapshotStore implements HealthSnapshotStore {

    /** Eventos que o consumidor do Health processa (ADR-0018 §2). */
    static final List<String> CONSUMED_EVENTS = List.of("analytics.projection-updated", "account.changed");

    /**
     * Upsert da marca: o primeiro evento fixa {@code dirty_since}; {@code due_at} = min(agora + quiet,
     * dirty_since + maxWait). Parâmetros: workspace, agora, agora + quiet, maxWait em ms.
     */
    private static final String MARK = """
            insert into health_workspaces as h (workspace_id, dirty_since, due_at) values (?, ?, ?)
            on conflict (workspace_id) do update set
              dirty_since = coalesce(h.dirty_since, excluded.dirty_since),
              due_at = least(excluded.due_at,
                             coalesce(h.dirty_since, excluded.dirty_since) + (? * interval '1 millisecond'))
            """;

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public JdbcHealthSnapshotStore(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void markDirty(WorkspaceId workspaceId, Instant now, Duration quiet, Duration maxWait) {
        jdbc.update(MARK, workspaceId.value(), Timestamp.from(now), Timestamp.from(now.plus(quiet)),
                maxWait.toMillis());
    }

    @Override
    public int markAllWithSnapshotsDirty(Instant now, Duration quiet, Duration maxWait) {
        return jdbc.update("""
                        insert into health_workspaces as h (workspace_id, dirty_since, due_at)
                        select distinct s.workspace_id, ?::timestamptz, ?::timestamptz from health_snapshots s
                        on conflict (workspace_id) do update set
                          dirty_since = coalesce(h.dirty_since, excluded.dirty_since),
                          due_at = least(excluded.due_at,
                                         coalesce(h.dirty_since, excluded.dirty_since)
                                           + (? * interval '1 millisecond'))
                        """,
                Timestamp.from(now), Timestamp.from(now.plus(quiet)), maxWait.toMillis());
    }

    @Override
    public Optional<WorkspaceId> claimDue(Instant now) {
        return jdbc.query("""
                        select workspace_id from health_workspaces
                        where due_at <= ?
                        order by due_at
                        limit 1
                        for update skip locked
                        """,
                (rs, row) -> new WorkspaceId(rs.getObject("workspace_id", UUID.class)), Timestamp.from(now))
                .stream().findFirst();
    }

    @Override
    public void lock(WorkspaceId workspaceId) {
        jdbc.update("insert into health_workspaces (workspace_id) values (?) on conflict do nothing",
                workspaceId.value());
        jdbc.queryForObject("select workspace_id from health_workspaces where workspace_id = ? for update",
                UUID.class, workspaceId.value());
    }

    @Override
    public void replace(WorkspaceId workspaceId, String algorithmVersion, List<HealthResult> results,
                        Instant computedAt) {
        UUID workspace = workspaceId.value();
        Timestamp at = Timestamp.from(computedAt);
        jdbc.update("delete from health_snapshots where workspace_id = ? and algorithm_version = ?", workspace,
                algorithmVersion);
        for (HealthResult result : results) {
            if (!result.algorithmVersion().equals(algorithmVersion)) {
                throw new IllegalArgumentException("Resultado de outra versão do algoritmo");
            }
            jdbc.update("""
                            insert into health_snapshots (workspace_id, algorithm_version, month, state, score,
                                                          partial, estimated, as_of, computed_at, result)
                            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                            """,
                    workspace, algorithmVersion, Date.valueOf(result.month().atDay(1)), result.state().name(),
                    result.score(), result.partial(), result.estimated(), Date.valueOf(result.asOf()), at,
                    json.writeValueAsString(HealthResultDocument.from(result)));
        }
        jdbc.update("""
                        insert into health_workspaces as h (workspace_id, computed_at) values (?, ?)
                        on conflict (workspace_id) do update
                          set dirty_since = null, due_at = null, computed_at = excluded.computed_at
                        """,
                workspace, at);
    }

    @Override
    public Optional<Instant> oldestDirtySince() {
        Timestamp oldest = jdbc.queryForObject("select min(dirty_since) from health_workspaces", Timestamp.class);
        return Optional.ofNullable(oldest).map(Timestamp::toInstant);
    }

    @Override
    public void postpone(WorkspaceId workspaceId, Instant dueAt) {
        jdbc.update("update health_workspaces set due_at = ? where workspace_id = ? and due_at is not null",
                Timestamp.from(dueAt), workspaceId.value());
    }

    @Override
    public List<WorkspaceId> workspacesToRebuild(String algorithmVersion, boolean all) {
        return jdbc.query("""
                        select w.id from workspaces w
                        where exists (select 1 from transactions t where t.workspace_id = w.id)
                          and (? or not exists (select 1 from health_snapshots s
                                                where s.workspace_id = w.id and s.algorithm_version = ?))
                        order by w.id
                        """,
                (rs, row) -> new WorkspaceId(rs.getObject("id", UUID.class)), all, algorithmVersion);
    }

    @Override
    public Optional<HealthResult> find(WorkspaceId workspaceId, String algorithmVersion, YearMonth month) {
        return findMonths(workspaceId, algorithmVersion, month, month).stream().findFirst();
    }

    @Override
    public List<HealthResult> findMonths(WorkspaceId workspaceId, String algorithmVersion, YearMonth from,
                                         YearMonth to) {
        return jdbc.query("""
                        select result::text as result from health_snapshots
                        where workspace_id = ? and algorithm_version = ? and month between ? and ?
                        order by month
                        """,
                (rs, row) -> json.readValue(rs.getString("result"), HealthResultDocument.class).toResult(),
                workspaceId.value(), algorithmVersion, Date.valueOf(from.atDay(1)), Date.valueOf(to.atDay(1)));
    }

    @Override
    public SnapshotState state(WorkspaceId workspaceId, String algorithmVersion) {
        UUID workspace = workspaceId.value();
        return jdbc.queryForObject("""
                        select h.dirty_since is not null as dirty, h.computed_at,
                               (select max(s.as_of) from health_snapshots s
                                 where s.workspace_id = ? and s.algorithm_version = ?) as latest_as_of,
                               exists (select 1 from transactions t where t.workspace_id = ?) as has_transactions,
                               (select count(*) from outbox_events o
                                 where o.workspace_id = ? and o.event_type in (?, ?)
                                   and not exists (select 1 from processed_events e
                                                    where e.consumer = ? and e.event_id = o.id)) as pending
                        from (select 1) as one
                        left join health_workspaces h on h.workspace_id = ?
                        """,
                (rs, row) -> {
                    Timestamp computedAt = rs.getTimestamp("computed_at");
                    LocalDate latestAsOf = rs.getObject("latest_as_of", LocalDate.class);
                    return new SnapshotState(rs.getBoolean("dirty"),
                            computedAt == null ? null : computedAt.toInstant(), latestAsOf != null, latestAsOf,
                            rs.getBoolean("has_transactions"), rs.getInt("pending"));
                },
                workspace, algorithmVersion, workspace, workspace, CONSUMED_EVENTS.get(0), CONSUMED_EVENTS.get(1),
                MarkHealthDirty.CONSUMER, workspace);
    }
}
