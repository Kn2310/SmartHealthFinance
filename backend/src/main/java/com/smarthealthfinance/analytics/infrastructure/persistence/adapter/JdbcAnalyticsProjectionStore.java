package com.smarthealthfinance.analytics.infrastructure.persistence.adapter;

import com.smarthealthfinance.analytics.application.port.AnalyticsProjectionStore;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * Escrita da projeção (ADR-0014 §3). O recálculo é um {@code delete} + {@code insert ... select} sobre
 * {@code transactions}: determinístico, idempotente e insensível à ordem dos eventos.
 * <p>
 * A semântica espelha {@code Transaction.cashFlowRole()}: só POSTED; INCOME, EXPENSE, REFUND e TRANSFER viram o
 * próprio papel; ADJUSTMENT fica de fora. O CASE não tem {@code ELSE} de propósito: um tipo novo viraria NULL e o
 * {@code not null} da coluna falharia, em vez de projetar errado em silêncio. O teste de paridade compara com a
 * referência em memória que usa o domínio.
 * <p>
 * Compra e estorno no cartão entram com {@code card_id} no lugar da conta (ADR-0026 §10); o pagamento de fatura é
 * uma transferência da conta de origem.
 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcAnalyticsProjectionStore implements AnalyticsProjectionStore {

    /** {@code %s} recebe o filtro de datas; vazio projeta o Workspace inteiro (rebuild). */
    private static final String PROJECT_TEMPLATE = """
            insert into analytics_daily_flows
              (workspace_id, day, account_id, card_id, flow, category_code, merchant_key, merchant_name, currency,
               amount, transaction_count)
            select t.workspace_id, t.occurred_on, t.account_id, t.card_id,
                   case t.type
                     when 'INCOME' then 'INCOME'
                     when 'EXPENSE' then 'EXPENSE'
                     when 'REFUND' then 'REFUND'
                     when 'TRANSFER' then 'TRANSFER'
                   end,
                   t.category_code, t.merchant_key, min(t.merchant_name), t.currency, sum(t.amount), count(*)
            from transactions t
            where t.workspace_id = ? and t.status = 'POSTED' and t.type <> 'ADJUSTMENT' %s
            group by t.workspace_id, t.occurred_on, t.account_id, t.card_id, t.type, t.category_code, t.merchant_key,
                     t.currency
            """;

    private static final String PROJECT_PERIOD = PROJECT_TEMPLATE.formatted("and t.occurred_on between ? and ?");

    private final JdbcTemplate jdbc;

    public JdbcAnalyticsProjectionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public OptionalInt lockWorkspace(WorkspaceId workspaceId) {
        jdbc.update("insert into analytics_projections (workspace_id) values (?) on conflict do nothing",
                workspaceId.value());
        Integer version = jdbc.queryForObject(
                "select version from analytics_projections where workspace_id = ? for update", Integer.class,
                workspaceId.value());
        return version == null ? OptionalInt.empty() : OptionalInt.of(version);
    }

    @Override
    public Optional<Instant> monthRefreshedAt(WorkspaceId workspaceId, YearMonth month) {
        return jdbc.query("select refreshed_at from analytics_projection_months where workspace_id = ? and month = ?",
                (rs, row) -> rs.getTimestamp("refreshed_at").toInstant(), workspaceId.value(),
                Date.valueOf(month.atDay(1))).stream().findFirst();
    }

    @Override
    public void recomputeMonth(WorkspaceId workspaceId, YearMonth month, Instant refreshedAt) {
        UUID workspace = workspaceId.value();
        Date first = Date.valueOf(month.atDay(1));
        Date last = Date.valueOf(month.atEndOfMonth());

        jdbc.update("delete from analytics_daily_flows where workspace_id = ? and day between ? and ?", workspace,
                first, last);
        jdbc.update(PROJECT_PERIOD, workspace, first, last);
        jdbc.update("""
                        insert into analytics_projection_months (workspace_id, month, refreshed_at) values (?, ?, ?)
                        on conflict (workspace_id, month) do update set refreshed_at = excluded.refreshed_at
                        """,
                workspace, first, Timestamp.from(refreshedAt));
    }

    @Override
    public void rebuild(WorkspaceId workspaceId, int version, Instant rebuiltAt) {
        UUID workspace = workspaceId.value();
        Timestamp at = Timestamp.from(rebuiltAt);

        jdbc.update("delete from analytics_daily_flows where workspace_id = ?", workspace);
        jdbc.update("delete from analytics_projection_months where workspace_id = ?", workspace);
        jdbc.update(PROJECT_TEMPLATE.formatted(""), workspace);
        jdbc.update("""
                        insert into analytics_projection_months (workspace_id, month, refreshed_at)
                        select distinct workspace_id, date_trunc('month', occurred_on)::date, ?::timestamptz
                        from transactions where workspace_id = ?
                        """,
                at, workspace);
        jdbc.update("update analytics_projections set version = ?, rebuilt_at = ? where workspace_id = ?", version,
                at, workspace);
    }

    /** Só leitura: pode rodar fora de transação (o runner monta a lista antes de abrir uma por Workspace). */
    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public List<WorkspaceId> workspacesToRebuild(int version, boolean all) {
        return jdbc.query("""
                        select distinct t.workspace_id
                        from transactions t
                        left join analytics_projections p on p.workspace_id = t.workspace_id
                        where ? or p.version is distinct from ?
                        order by t.workspace_id
                        """,
                (rs, row) -> new WorkspaceId(rs.getObject("workspace_id", UUID.class)), all, version);
    }
}
