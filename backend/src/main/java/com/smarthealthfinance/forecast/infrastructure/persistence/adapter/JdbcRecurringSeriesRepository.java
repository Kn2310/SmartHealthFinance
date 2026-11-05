package com.smarthealthfinance.forecast.infrastructure.persistence.adapter;

import com.smarthealthfinance.forecast.application.port.RecurringSeriesRepository;
import com.smarthealthfinance.forecast.domain.enums.ChannelKind;
import com.smarthealthfinance.forecast.domain.enums.Confidence;
import com.smarthealthfinance.forecast.domain.enums.Periodicity;
import com.smarthealthfinance.forecast.domain.enums.SeriesDirection;
import com.smarthealthfinance.forecast.domain.enums.SeriesOrigin;
import com.smarthealthfinance.forecast.domain.enums.SeriesStatus;
import com.smarthealthfinance.forecast.domain.model.Channel;
import com.smarthealthfinance.forecast.domain.model.Range;
import com.smarthealthfinance.forecast.domain.model.RecurringSeries;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.shared.domain.UuidV7;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Séries recorrentes (ADR-0031 §4). Toda leitura e escrita é por Workspace. A redetecção escreve só as colunas
 * detectadas ({@link #upsertDetected}); o estado do usuário só muda em {@link #saveUserState}. Nada apaga linhas.
 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcRecurringSeriesRepository implements RecurringSeriesRepository {

    private static final String SELECT = """
            select direction, series_key, label, channel_kind, channel_id, periodicity, expected_day,
                   expected_amount, amount_min, amount_max, currency, confidence, occurrences, algorithm_version,
                   status, origin, user_expected_amount, user_expected_day, status_changed_at
            from forecast_recurring_series
            """;

    private final JdbcTemplate jdbc;

    public JdbcRecurringSeriesRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<RecurringSeries> findAll(WorkspaceId workspaceId) {
        return jdbc.query(SELECT + " where workspace_id = ? order by direction, series_key", MAPPER,
                workspaceId.value());
    }

    @Override
    public List<SeriesRef> findAllRefs(WorkspaceId workspaceId) {
        return jdbc.query("select id, direction, series_key from forecast_recurring_series where workspace_id = ?",
                REF_MAPPER, workspaceId.value());
    }

    @Override
    public Optional<SeriesRef> findRef(WorkspaceId workspaceId, UUID seriesId) {
        return jdbc.query("select id, direction, series_key from forecast_recurring_series "
                + "where workspace_id = ? and id = ?", REF_MAPPER, workspaceId.value(), seriesId).stream().findFirst();
    }

    private static final RowMapper<SeriesRef> REF_MAPPER = (rs, row) -> new SeriesRef(rs.getObject("id", UUID.class),
            SeriesDirection.valueOf(rs.getString("direction")), rs.getString("series_key"));

    @Override
    public Optional<RecurringSeries> find(WorkspaceId workspaceId, SeriesDirection direction, String key) {
        return jdbc.query(SELECT + " where workspace_id = ? and direction = ? and series_key = ?", MAPPER,
                workspaceId.value(), direction.name(), key).stream().findFirst();
    }

    @Override
    public Optional<RecurringSeries> findForUpdate(WorkspaceId workspaceId, SeriesDirection direction, String key) {
        return jdbc.query(SELECT + " where workspace_id = ? and direction = ? and series_key = ? for update", MAPPER,
                workspaceId.value(), direction.name(), key).stream().findFirst();
    }

    @Override
    public void upsertDetected(WorkspaceId workspaceId, Collection<RecurringSeries> series, Instant now) {
        for (RecurringSeries s : series) {
            jdbc.update(connection -> {
                var statement = connection.prepareStatement("""
                        insert into forecast_recurring_series
                          (workspace_id, direction, series_key, label, channel_kind, channel_id, periodicity,
                           expected_day, expected_amount, amount_min, amount_max, currency, confidence, occurrences,
                           algorithm_version, status, origin, user_expected_amount, user_expected_day,
                           status_changed_at, created_at, updated_at, id)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        on conflict (workspace_id, direction, series_key) do update set
                          label = excluded.label, channel_kind = excluded.channel_kind,
                          channel_id = excluded.channel_id, periodicity = excluded.periodicity,
                          expected_day = excluded.expected_day, expected_amount = excluded.expected_amount,
                          amount_min = excluded.amount_min, amount_max = excluded.amount_max,
                          currency = excluded.currency, confidence = excluded.confidence,
                          occurrences = excluded.occurrences, algorithm_version = excluded.algorithm_version,
                          updated_at = excluded.updated_at
                        """);
                Timestamp at = Timestamp.from(now);
                int i = 1;
                statement.setObject(i++, workspaceId.value());
                statement.setString(i++, s.direction().name());
                statement.setString(i++, s.key());
                statement.setString(i++, s.label());
                statement.setString(i++, s.channel().kind().name());
                statement.setObject(i++, s.channel().id());
                statement.setString(i++, s.periodicity().name());
                statement.setInt(i++, s.expectedDay());
                statement.setBigDecimal(i++, s.expectedAmount().amount());
                statement.setBigDecimal(i++, s.amountRange().min().amount());
                statement.setBigDecimal(i++, s.amountRange().max().amount());
                statement.setString(i++, s.expectedAmount().currency().getCurrencyCode());
                statement.setString(i++, s.confidence().name());
                statement.setArray(i++, connection.createArrayOf("uuid", s.occurrences().toArray()));
                statement.setString(i++, s.algorithmVersion());
                statement.setString(i++, s.status().name());
                statement.setString(i++, s.origin().name());
                statement.setBigDecimal(i++, s.userExpectedAmount() == null ? null : s.userExpectedAmount().amount());
                statement.setObject(i++, s.userExpectedDay(), java.sql.Types.SMALLINT);
                statement.setTimestamp(i++, s.statusChangedAt() == null ? null : Timestamp.from(s.statusChangedAt()));
                statement.setTimestamp(i++, at);
                statement.setTimestamp(i++, at);
                statement.setObject(i, UuidV7.generate(now));
                return statement;
            });
        }
    }

    @Override
    public void saveUserState(WorkspaceId workspaceId, RecurringSeries series, Instant now) {
        jdbc.update("""
                        update forecast_recurring_series
                        set status = ?, user_expected_amount = ?, user_expected_day = ?, status_changed_at = ?,
                            updated_at = ?
                        where workspace_id = ? and direction = ? and series_key = ?
                        """,
                series.status().name(), series.userExpectedAmount() == null ? null : series.userExpectedAmount().amount(),
                series.userExpectedDay(),
                series.statusChangedAt() == null ? null : Timestamp.from(series.statusChangedAt()),
                Timestamp.from(now), workspaceId.value(), series.direction().name(), series.key());
    }

    private static final RowMapper<RecurringSeries> MAPPER = (rs, row) -> {
        Currency currency = Currency.getInstance(rs.getString("currency"));
        Array occurrences = rs.getArray("occurrences");
        List<UUID> ids = Arrays.stream((Object[]) occurrences.getArray()).map(o -> (UUID) o).toList();
        java.math.BigDecimal userAmount = rs.getBigDecimal("user_expected_amount");
        Timestamp changedAt = rs.getTimestamp("status_changed_at");
        Integer userDay = (Integer) rs.getObject("user_expected_day");
        return new RecurringSeries(SeriesDirection.valueOf(rs.getString("direction")), rs.getString("series_key"),
                rs.getString("label"),
                new Channel(ChannelKind.valueOf(rs.getString("channel_kind")), rs.getObject("channel_id", UUID.class)),
                Periodicity.valueOf(rs.getString("periodicity")), rs.getInt("expected_day"),
                new Money(rs.getBigDecimal("expected_amount"), currency),
                new Range(new Money(rs.getBigDecimal("amount_min"), currency),
                        new Money(rs.getBigDecimal("amount_max"), currency)),
                Confidence.valueOf(rs.getString("confidence")), ids, rs.getString("algorithm_version"),
                SeriesStatus.valueOf(rs.getString("status")), SeriesOrigin.valueOf(rs.getString("origin")),
                userAmount == null ? null : new Money(userAmount, currency), userDay,
                changedAt == null ? null : changedAt.toInstant());
    };

}
