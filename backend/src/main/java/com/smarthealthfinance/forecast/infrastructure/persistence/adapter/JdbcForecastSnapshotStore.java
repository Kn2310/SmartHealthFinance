package com.smarthealthfinance.forecast.infrastructure.persistence.adapter;

import com.smarthealthfinance.forecast.application.port.ForecastSnapshotStore;
import com.smarthealthfinance.forecast.application.usecase.MarkForecastDirty;
import com.smarthealthfinance.forecast.domain.model.ForecastResult;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Snapshot do Forecast e marcação com debounce (ADR-0031 §3/§4). Toda leitura e escrita é por Workspace. O Forecast
 * conhece os eventos que consome só pelo nome.
 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcForecastSnapshotStore implements ForecastSnapshotStore {

    /** Eventos que o consumidor do Forecast processa (ADR-0031 §2). */
    static final List<String> CONSUMED_EVENTS = List.of("analytics.projection-updated", "account.changed",
            "card.created", "card.updated", "card.archived", "card.reactivated", "invoice.closed");

    /**
     * Upsert da marca: o primeiro evento fixa {@code dirty_since}; {@code due_at} = min(agora + quiet,
     * dirty_since + maxWait). Parâmetros: workspace, agora, agora + quiet, maxWait em ms.
     */
    private static final String MARK = """
            insert into forecast_workspaces as f (workspace_id, dirty_since, due_at) values (?, ?, ?)
            on conflict (workspace_id) do update set
              dirty_since = coalesce(f.dirty_since, excluded.dirty_since),
              due_at = least(excluded.due_at,
                             coalesce(f.dirty_since, excluded.dirty_since) + (? * interval '1 millisecond'))
            """;

    private final JdbcTemplate jdbc;

    public JdbcForecastSnapshotStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void markDirty(WorkspaceId workspaceId, Instant now, Duration quiet, Duration maxWait) {
        jdbc.update(MARK, workspaceId.value(), Timestamp.from(now), Timestamp.from(now.plus(quiet)),
                maxWait.toMillis());
    }

    @Override
    public void markDueNow(WorkspaceId workspaceId, Instant now) {
        jdbc.update("""
                        insert into forecast_workspaces as f (workspace_id, dirty_since, due_at) values (?, ?, ?)
                        on conflict (workspace_id) do update set
                          dirty_since = coalesce(f.dirty_since, excluded.dirty_since),
                          due_at = least(f.due_at, excluded.due_at)
                        """,
                workspaceId.value(), Timestamp.from(now), Timestamp.from(now));
    }

    @Override
    public int markAllWithSnapshotsDirty(Instant now, Duration quiet, Duration maxWait) {
        return jdbc.update("""
                        insert into forecast_workspaces as f (workspace_id, dirty_since, due_at)
                        select distinct s.workspace_id, ?::timestamptz, ?::timestamptz from forecast_snapshots s
                        on conflict (workspace_id) do update set
                          dirty_since = coalesce(f.dirty_since, excluded.dirty_since),
                          due_at = least(excluded.due_at,
                                         coalesce(f.dirty_since, excluded.dirty_since)
                                           + (? * interval '1 millisecond'))
                        """,
                Timestamp.from(now), Timestamp.from(now.plus(quiet)), maxWait.toMillis());
    }

    @Override
    public Optional<WorkspaceId> claimDue(Instant now) {
        return jdbc.query("""
                        select workspace_id from forecast_workspaces
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
        jdbc.update("insert into forecast_workspaces (workspace_id) values (?) on conflict do nothing",
                workspaceId.value());
        jdbc.queryForObject("select workspace_id from forecast_workspaces where workspace_id = ? for update",
                UUID.class, workspaceId.value());
    }

    @Override
    public void replace(WorkspaceId workspaceId, ForecastResult result, Instant computedAt) {
        UUID workspace = workspaceId.value();
        Timestamp at = Timestamp.from(computedAt);
        jdbc.update("""
                        insert into forecast_snapshots (workspace_id, algorithm_version, as_of, computed_at, result)
                        values (?, ?, ?, ?, ?::jsonb)
                        on conflict (workspace_id, algorithm_version) do update
                          set as_of = excluded.as_of, computed_at = excluded.computed_at, result = excluded.result
                        """,
                workspace, result.algorithmVersion(), Date.valueOf(result.asOf()), at,
                ForecastResultDocument.toJson(result));
        jdbc.update("""
                        insert into forecast_workspaces as f (workspace_id, computed_at) values (?, ?)
                        on conflict (workspace_id) do update
                          set dirty_since = null, due_at = null, computed_at = excluded.computed_at
                        """,
                workspace, at);
    }

    @Override
    public Optional<Instant> oldestDirtySince() {
        Timestamp oldest = jdbc.queryForObject("select min(dirty_since) from forecast_workspaces", Timestamp.class);
        return Optional.ofNullable(oldest).map(Timestamp::toInstant);
    }

    @Override
    public void postpone(WorkspaceId workspaceId, Instant dueAt) {
        jdbc.update("update forecast_workspaces set due_at = ? where workspace_id = ? and due_at is not null",
                Timestamp.from(dueAt), workspaceId.value());
    }

    @Override
    public List<WorkspaceId> workspacesToRebuild(String algorithmVersion, boolean all) {
        return jdbc.query("""
                        select w.id from workspaces w
                        where exists (select 1 from transactions t where t.workspace_id = w.id)
                          and (? or not exists (select 1 from forecast_snapshots s
                                                where s.workspace_id = w.id and s.algorithm_version = ?))
                        order by w.id
                        """,
                (rs, row) -> new WorkspaceId(rs.getObject("id", UUID.class)), all, algorithmVersion);
    }

    @Override
    public Optional<ForecastResult> find(WorkspaceId workspaceId, String algorithmVersion) {
        return jdbc.query("""
                        select result::text as result from forecast_snapshots
                        where workspace_id = ? and algorithm_version = ?
                        """,
                (rs, row) -> ForecastResultDocument.fromJson(rs.getString("result")), workspaceId.value(),
                algorithmVersion).stream().findFirst();
    }

    @Override
    public SnapshotState state(WorkspaceId workspaceId, String algorithmVersion) {
        UUID workspace = workspaceId.value();
        String placeholders = String.join(", ", CONSUMED_EVENTS.stream().map(event -> "?").toList());
        List<Object> args = new ArrayList<>();
        args.add(workspace);
        args.add(algorithmVersion);
        args.add(workspace);
        args.add(workspace);
        args.addAll(CONSUMED_EVENTS);
        args.add(MarkForecastDirty.CONSUMER);
        args.add(workspace);
        return jdbc.queryForObject("""
                        select h.dirty_since is not null as dirty, h.computed_at,
                               (select s.as_of from forecast_snapshots s
                                 where s.workspace_id = ? and s.algorithm_version = ?) as latest_as_of,
                               exists (select 1 from transactions t where t.workspace_id = ?) as has_transactions,
                               (select count(*) from outbox_events o
                                 where o.workspace_id = ? and o.event_type in (%s)
                                   and (h.computed_at is null or o.occurred_at > h.computed_at)
                                   and not exists (select 1 from processed_events e
                                                    where e.consumer = ? and e.event_id = o.id)) as pending
                        from (select 1) as one
                        left join forecast_workspaces h on h.workspace_id = ?
                        """.formatted(placeholders),
                (rs, row) -> {
                    Timestamp computedAt = rs.getTimestamp("computed_at");
                    LocalDate latestAsOf = rs.getObject("latest_as_of", LocalDate.class);
                    return new SnapshotState(rs.getBoolean("dirty"),
                            computedAt == null ? null : computedAt.toInstant(), latestAsOf != null, latestAsOf,
                            rs.getBoolean("has_transactions"), rs.getInt("pending"));
                },
                args.toArray());
    }
}
