package com.smarthealthfinance.forecast.infrastructure.persistence.adapter;

import com.smarthealthfinance.forecast.application.port.MonthOutcomeStore;
import com.smarthealthfinance.forecast.domain.enums.Confidence;
import com.smarthealthfinance.forecast.domain.model.MonthOutcome;
import com.smarthealthfinance.forecast.domain.model.MonthOutcome.Error;
import com.smarthealthfinance.forecast.domain.model.MonthOutcome.Figures;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.Money;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Currency;
import java.util.List;
import java.util.Optional;

/**
 * Previsto × realizado (ADR-0031 §6). Toda leitura e escrita é por Workspace. O congelamento é
 * {@code ON CONFLICT DO NOTHING}; a reconciliação só toca o realizado e o erro de linhas ainda não liquidadas; depois
 * de {@code settled_at} a linha é imutável. Nada apaga linhas.
 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcMonthOutcomeStore implements MonthOutcomeStore {

    private static final String SELECT = """
            select month, algorithm_version, currency, confidence,
                   projected_expense, projected_income, projected_net,
                   projected_expense_low, projected_income_low, projected_net_low,
                   projected_expense_high, projected_income_high, projected_net_high,
                   realized_expense, realized_income, realized_net,
                   error_expense, error_income, error_net, error_expense_percent, error_income_percent,
                   expense_within_range, income_within_range, frozen_at, settled_at
            from forecast_month_outcomes
            """;

    private final JdbcTemplate jdbc;

    public JdbcMonthOutcomeStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean freeze(WorkspaceId workspaceId, MonthOutcome frozen, Instant now) {
        return jdbc.update("""
                        insert into forecast_month_outcomes
                          (workspace_id, month, algorithm_version, currency, confidence,
                           projected_expense, projected_income, projected_net,
                           projected_expense_low, projected_income_low, projected_net_low,
                           projected_expense_high, projected_income_high, projected_net_high, frozen_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        on conflict (workspace_id, month, algorithm_version) do nothing
                        """,
                workspaceId.value(), Date.valueOf(frozen.month().atDay(1)), frozen.algorithmVersion(),
                frozen.projected().net().currency().getCurrencyCode(), frozen.confidence().name(),
                frozen.projected().expense().amount(), frozen.projected().income().amount(),
                frozen.projected().net().amount(), frozen.projectedLow().expense().amount(),
                frozen.projectedLow().income().amount(), frozen.projectedLow().net().amount(),
                frozen.projectedHigh().expense().amount(), frozen.projectedHigh().income().amount(),
                frozen.projectedHigh().net().amount(), Timestamp.from(now)) == 1;
    }

    @Override
    public boolean reconcile(WorkspaceId workspaceId, MonthOutcome reconciled) {
        Figures realized = reconciled.realized();
        Error error = reconciled.error();
        if (realized == null || error == null) {
            throw new IllegalArgumentException("Reconciliação exige realizado e erro");
        }
        return jdbc.update("""
                        update forecast_month_outcomes
                        set realized_expense = ?, realized_income = ?, realized_net = ?,
                            error_expense = ?, error_income = ?, error_net = ?,
                            error_expense_percent = ?, error_income_percent = ?,
                            expense_within_range = ?, income_within_range = ?
                        where workspace_id = ? and month = ? and algorithm_version = ? and settled_at is null
                        """,
                realized.expense().amount(), realized.income().amount(), realized.net().amount(),
                error.absolute().expense().amount(), error.absolute().income().amount(),
                error.absolute().net().amount(), error.expensePercent(), error.incomePercent(),
                error.expenseWithinRange(), error.incomeWithinRange(), workspaceId.value(),
                Date.valueOf(reconciled.month().atDay(1)), reconciled.algorithmVersion()) == 1;
    }

    @Override
    public boolean settle(WorkspaceId workspaceId, YearMonth month, String algorithmVersion, Instant now) {
        return jdbc.update("""
                        update forecast_month_outcomes set settled_at = ?
                        where workspace_id = ? and month = ? and algorithm_version = ? and settled_at is null
                        """,
                Timestamp.from(now), workspaceId.value(), Date.valueOf(month.atDay(1)), algorithmVersion) == 1;
    }

    @Override
    public Optional<Stored> find(WorkspaceId workspaceId, YearMonth month, String algorithmVersion) {
        return jdbc.query(SELECT + " where workspace_id = ? and month = ? and algorithm_version = ?", MAPPER,
                workspaceId.value(), Date.valueOf(month.atDay(1)), algorithmVersion).stream().findFirst();
    }

    @Override
    public List<Stored> findUnsettled(WorkspaceId workspaceId, String algorithmVersion) {
        return jdbc.query(SELECT + " where workspace_id = ? and algorithm_version = ? and settled_at is null"
                + " order by month", MAPPER, workspaceId.value(), algorithmVersion);
    }

    @Override
    public List<Stored> findAll(WorkspaceId workspaceId, String algorithmVersion) {
        return jdbc.query(SELECT + " where workspace_id = ? and algorithm_version = ? order by month", MAPPER,
                workspaceId.value(), algorithmVersion);
    }

    private static final RowMapper<Stored> MAPPER = (rs, row) -> {
        Currency currency = Currency.getInstance(rs.getString("currency"));
        MonthOutcome outcome = new MonthOutcome(YearMonth.from(rs.getObject("month", LocalDate.class)),
                rs.getString("algorithm_version"),
                figures(rs, currency, "projected_expense", "projected_income", "projected_net"),
                figures(rs, currency, "projected_expense_low", "projected_income_low", "projected_net_low"),
                figures(rs, currency, "projected_expense_high", "projected_income_high", "projected_net_high"),
                Confidence.valueOf(rs.getString("confidence")),
                figures(rs, currency, "realized_expense", "realized_income", "realized_net"), error(rs, currency));
        Timestamp settledAt = rs.getTimestamp("settled_at");
        return new Stored(outcome, rs.getTimestamp("frozen_at").toInstant(),
                settledAt == null ? null : settledAt.toInstant());
    };

    /** Nulo se a primeira coluna é nula (ainda sem realizado). */
    private static Figures figures(ResultSet rs, Currency currency, String expense, String income, String net)
            throws SQLException {
        BigDecimal first = rs.getBigDecimal(expense);
        if (first == null) {
            return null;
        }
        return new Figures(new Money(first, currency), new Money(rs.getBigDecimal(income), currency),
                new Money(rs.getBigDecimal(net), currency));
    }

    private static Error error(ResultSet rs, Currency currency) throws SQLException {
        Figures absolute = figures(rs, currency, "error_expense", "error_income", "error_net");
        if (absolute == null) {
            return null;
        }
        return new Error(absolute, rs.getBigDecimal("error_expense_percent"), rs.getBigDecimal("error_income_percent"),
                rs.getBoolean("expense_within_range"), rs.getBoolean("income_within_range"));
    }
}
