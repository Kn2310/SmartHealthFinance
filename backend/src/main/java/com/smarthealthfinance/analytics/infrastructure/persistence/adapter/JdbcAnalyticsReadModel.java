package com.smarthealthfinance.analytics.infrastructure.persistence.adapter;

import com.smarthealthfinance.analytics.application.port.AnalyticsReadModel;
import com.smarthealthfinance.analytics.application.usecase.RebuildAnalyticsProjection;
import com.smarthealthfinance.analytics.application.usecase.RefreshAnalyticsProjection;
import com.smarthealthfinance.analytics.domain.model.CategoryFlow;
import com.smarthealthfinance.analytics.domain.model.DailyFlow;
import com.smarthealthfinance.analytics.domain.model.ExpenseItem;
import com.smarthealthfinance.analytics.domain.model.FlowEntry;
import com.smarthealthfinance.analytics.domain.model.FlowTotals;
import com.smarthealthfinance.analytics.domain.model.MerchantFlow;
import com.smarthealthfinance.analytics.domain.model.MonthlyFlow;
import com.smarthealthfinance.analytics.domain.model.ProjectionFreshness;
import com.smarthealthfinance.analytics.domain.valueobject.AnalyticsPeriod;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.enums.CashFlowRole;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

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
import java.util.UUID;

/**
 * Leitura da projeção de Analytics (ADR-0014 §5), sempre com {@code workspace_id} no predicado e somas em NUMERIC.
 * <p>
 * Receitas, despesas e reembolsos só de contas ativas incluídas no total, como no fluxo de caixa do Overview
 * (ADR-0006 §9); o filtro é aplicado na leitura, juntando com {@code accounts}, para que arquivar uma conta ou
 * tirá-la do total valha na hora. Transferências são informativas e contam todas as contas.
 */
@Repository
public class JdbcAnalyticsReadModel implements AnalyticsReadModel {

    /** Linha que entra no fluxo de caixa: papel do fluxo e conta ativa incluída no total. */
    private static final String IN_CASH_FLOW = """
            f.flow in ('INCOME', 'EXPENSE', 'REFUND') and a.status = 'ACTIVE' and a.included_in_total""";

    private static final String FROM = """
            from analytics_daily_flows f
            join accounts a on a.id = f.account_id and a.workspace_id = f.workspace_id
            where f.workspace_id = ? and f.day between ? and ?
            """;

    private final JdbcTemplate jdbc;

    public JdbcAnalyticsReadModel(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<MonthlyFlow> monthly(WorkspaceId workspaceId, AnalyticsPeriod period, Currency currency) {
        return jdbc.query("""
                        select date_trunc('month', f.day)::date as month,
                               coalesce(sum(f.amount) filter (where f.flow = 'INCOME' and %1$s), 0) as income,
                               coalesce(sum(f.amount) filter (where f.flow = 'EXPENSE' and %1$s), 0) as expense,
                               coalesce(sum(f.amount) filter (where f.flow = 'REFUND' and %1$s), 0) as refunds,
                               coalesce(sum(f.transaction_count) filter (where f.flow = 'TRANSFER'), 0)
                                 as transfer_count,
                               coalesce(sum(f.amount) filter (where f.flow = 'TRANSFER'), 0) as transfer_volume
                        %2$s
                        group by 1
                        order by 1
                        """.formatted(IN_CASH_FLOW, FROM),
                (rs, row) -> new MonthlyFlow(
                        YearMonth.from(rs.getObject("month", LocalDate.class)),
                        new FlowTotals(money(rs, "income", currency), money(rs, "expense", currency),
                                money(rs, "refunds", currency), rs.getInt("transfer_count"),
                                money(rs, "transfer_volume", currency))),
                workspaceId.value(), Date.valueOf(period.from()), Date.valueOf(period.to()));
    }

    @Override
    public List<CategoryFlow> byCategory(WorkspaceId workspaceId, AnalyticsPeriod period, Currency currency) {
        return jdbc.query("""
                        select f.flow, f.category_code, sum(f.amount) as amount,
                               sum(f.transaction_count) as transaction_count
                        %s
                          and %s
                        group by f.flow, f.category_code
                        order by f.flow, amount desc, f.category_code nulls last
                        """.formatted(FROM, IN_CASH_FLOW),
                (rs, row) -> new CategoryFlow(role(rs), rs.getString("category_code"),
                        money(rs, "amount", currency), rs.getInt("transaction_count")),
                workspaceId.value(), Date.valueOf(period.from()), Date.valueOf(period.to()));
    }

    @Override
    public List<MerchantFlow> byMerchant(WorkspaceId workspaceId, AnalyticsPeriod period, Currency currency) {
        return jdbc.query("""
                        select f.flow, f.merchant_key, min(f.merchant_name) as merchant_name, f.category_code,
                               sum(f.amount) as amount, sum(f.transaction_count) as transaction_count
                        %s
                          and %s
                        group by f.flow, f.merchant_key, f.category_code
                        order by f.flow, amount desc, f.merchant_key nulls last, f.category_code nulls last
                        """.formatted(FROM, IN_CASH_FLOW),
                (rs, row) -> new MerchantFlow(role(rs), rs.getString("merchant_key"), rs.getString("merchant_name"),
                        rs.getString("category_code"), money(rs, "amount", currency),
                        rs.getInt("transaction_count")),
                workspaceId.value(), Date.valueOf(period.from()), Date.valueOf(period.to()));
    }

    @Override
    public List<DailyFlow> daily(WorkspaceId workspaceId, AnalyticsPeriod period, Currency currency) {
        return jdbc.query("""
                        select f.day, f.flow, sum(f.amount) as amount, sum(f.transaction_count) as transaction_count
                        %s
                          and %s
                        group by f.day, f.flow
                        order by f.day, f.flow
                        """.formatted(FROM, IN_CASH_FLOW),
                (rs, row) -> new DailyFlow(rs.getObject("day", LocalDate.class), role(rs),
                        money(rs, "amount", currency), rs.getInt("transaction_count")),
                workspaceId.value(), Date.valueOf(period.from()), Date.valueOf(period.to()));
    }

    @Override
    public List<FlowEntry> entries(WorkspaceId workspaceId, AnalyticsPeriod period, Currency currency) {
        return jdbc.query("""
                        select f.day, f.flow, f.category_code, f.merchant_key, min(f.merchant_name) as merchant_name,
                               sum(f.amount) as amount, sum(f.transaction_count) as transaction_count
                        %s
                          and %s
                        group by f.day, f.flow, f.category_code, f.merchant_key
                        order by f.day, f.flow, f.category_code nulls last, f.merchant_key nulls last
                        """.formatted(FROM, IN_CASH_FLOW),
                (rs, row) -> new FlowEntry(rs.getObject("day", LocalDate.class), role(rs),
                        rs.getString("category_code"), rs.getString("merchant_key"), rs.getString("merchant_name"),
                        money(rs, "amount", currency), rs.getInt("transaction_count")),
                workspaceId.value(), Date.valueOf(period.from()), Date.valueOf(period.to()));
    }

    @Override
    public Optional<LocalDate> firstActivity(WorkspaceId workspaceId) {
        return Optional.ofNullable(jdbc.queryForObject("""
                        select min(f.day)
                        from analytics_daily_flows f
                        join accounts a on a.id = f.account_id and a.workspace_id = f.workspace_id
                        where f.workspace_id = ? and %s
                        """.formatted(IN_CASH_FLOW),
                LocalDate.class, workspaceId.value()));
    }

    @Override
    public List<ExpenseItem> largestExpenses(WorkspaceId workspaceId, AnalyticsPeriod period, int limit,
                                             Currency currency) {
        return jdbc.query("""
                        select t.id, t.occurred_on, t.description, t.merchant_name, t.category_code, t.amount
                        from transactions t
                        join accounts a on a.id = t.account_id and a.workspace_id = t.workspace_id
                        where t.workspace_id = ? and t.status = 'POSTED' and t.type = 'EXPENSE'
                          and t.occurred_on between ? and ?
                          and a.status = 'ACTIVE' and a.included_in_total
                        order by t.amount desc, t.occurred_on desc, t.id
                        limit ?
                        """,
                (rs, row) -> new ExpenseItem(rs.getObject("id", UUID.class), rs.getObject("occurred_on", LocalDate.class),
                        rs.getString("description"), rs.getString("merchant_name"), rs.getString("category_code"),
                        money(rs, "amount", currency)),
                workspaceId.value(), Date.valueOf(period.from()), Date.valueOf(period.to()), limit);
    }

    /**
     * Pendentes = eventos do Workspace posteriores ao último rebuild (o rebuild já reflete tudo o que veio antes)
     * que o consumidor do Analytics ainda não marcou na inbox.
     */
    @Override
    public ProjectionFreshness freshness(WorkspaceId workspaceId) {
        return jdbc.queryForObject("""
                        select p.version, p.rebuilt_at,
                               (select max(m.refreshed_at) from analytics_projection_months m
                                 where m.workspace_id = ?) as month_refreshed_at,
                               exists (select 1 from transactions t where t.workspace_id = ? and t.status = 'POSTED')
                                 as has_posted,
                               (select count(*) from outbox_events o
                                 where o.workspace_id = ?
                                   and (o.event_type like 'transaction.%%' or o.event_type = 'import.completed')
                                   and (p.rebuilt_at is null or o.occurred_at > p.rebuilt_at)
                                   and not exists (select 1 from processed_events e
                                                    where e.consumer = ? and e.event_id = o.id)) as pending
                        from (select 1) as one
                        left join analytics_projections p on p.workspace_id = ?
                        """.replace("%%", "%"),
                (rs, row) -> {
                    Integer version = (Integer) rs.getObject("version");
                    Instant refreshedAt = latest(rs.getTimestamp("rebuilt_at"), rs.getTimestamp("month_refreshed_at"));
                    return ProjectionFreshness.resolve(version != null && version == RebuildAnalyticsProjection.VERSION,
                            rs.getBoolean("has_posted"), refreshedAt, rs.getInt("pending"));
                },
                workspaceId.value(), workspaceId.value(), workspaceId.value(), RefreshAnalyticsProjection.CONSUMER,
                workspaceId.value());
    }

    private static Instant latest(Timestamp a, Timestamp b) {
        if (a == null) {
            return b == null ? null : b.toInstant();
        }
        return b == null || a.after(b) ? a.toInstant() : b.toInstant();
    }

    private static CashFlowRole role(ResultSet rs) throws SQLException {
        return CashFlowRole.valueOf(rs.getString("flow"));
    }

    private static Money money(ResultSet rs, String column, Currency currency) throws SQLException {
        return new Money(rs.getBigDecimal(column), currency);
    }
}
