package com.smarthealthfinance.overview.infrastructure.persistence.adapter;

import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.overview.application.port.OverviewReadModel;
import com.smarthealthfinance.overview.application.port.OverviewReadModel.CardRef;
import com.smarthealthfinance.overview.domain.enums.DataSource;
import com.smarthealthfinance.overview.domain.model.CashFlow;
import com.smarthealthfinance.overview.domain.valueobject.OverviewPeriod;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.enums.AdjustmentDirection;
import com.smarthealthfinance.transactions.domain.enums.TransactionSource;
import com.smarthealthfinance.transactions.domain.enums.TransactionStatus;
import com.smarthealthfinance.transactions.domain.enums.TransactionType;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Currency;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Read model do Overview em SQL agregado (ADR-0006). Lê as tabelas de Accounts e Transactions apenas para leitura,
 * sempre com {@code workspace_id} no predicado; o cálculo roda no PostgreSQL com NUMERIC, nunca em ponto flutuante.
 * <p>
 * A semântica espelha {@code Transaction.balanceEffectOn}: só POSTED conta; INCOME/REFUND creditam, EXPENSE debita,
 * ADJUSTMENT segue a direção e TRANSFER debita a origem e credita o destino. Os CASEs abaixo não têm {@code ELSE}
 * de propósito: um tipo novo viraria NULL e o teste de paridade falharia em vez de somar errado em silêncio.
 * Cada método é uma única consulta agregada (sem N+1), apoiada por {@code ix_transactions_posted}.
 */
@Repository
public class JdbcOverviewReadModel implements OverviewReadModel {

    private final JdbcTemplate jdbc;

    public JdbcOverviewReadModel(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Cada transação gera uma "perna" na origem e, se for transferência, outra no destino. */
    @Override
    public Map<AccountId, AccountFigures> accountFigures(WorkspaceId workspaceId, OverviewPeriod period,
                                                         Currency currency) {
        Map<AccountId, AccountFigures> figures = new HashMap<>();

        jdbc.query("""
                        select leg.account_id,
                               sum(leg.delta) as balance,
                               count(*) filter (where leg.occurred_on >= ?) as movements
                        from (
                          select t.account_id, t.occurred_on,
                                 case
                                   when t.type in ('INCOME', 'REFUND') then t.amount
                                   when t.type in ('EXPENSE', 'TRANSFER') then -t.amount
                                   when t.type = 'ADJUSTMENT' and t.adjustment_direction = 'INCREASE' then t.amount
                                   when t.type = 'ADJUSTMENT' and t.adjustment_direction = 'DECREASE' then -t.amount
                                 end as delta
                          from transactions t
                          where t.workspace_id = ? and t.status = 'POSTED' and t.occurred_on <= ?
                            and t.account_id is not null
                          union all
                          select t.destination_account_id, t.occurred_on, t.amount
                          from transactions t
                          where t.workspace_id = ? and t.status = 'POSTED' and t.type = 'TRANSFER'
                            and t.destination_account_id is not null and t.occurred_on <= ?
                        ) leg
                        group by leg.account_id
                        """,
                rs -> {
                    figures.put(new AccountId(rs.getObject("account_id", UUID.class)),
                            new AccountFigures(new Money(rs.getBigDecimal("balance"), currency),
                                    rs.getInt("movements")));
                },
                period.from(), workspaceId.value(), period.to(), workspaceId.value(), period.to());

        return figures;
    }

    @Override
    public CashFlow cashFlow(WorkspaceId workspaceId, OverviewPeriod period, Currency currency) {
        return jdbc.queryForObject("""
                        select coalesce(sum(t.amount) filter (where t.type = 'INCOME'), 0) as income,
                               coalesce(sum(t.amount) filter (where t.type = 'EXPENSE'), 0) as expense,
                               coalesce(sum(t.amount) filter (where t.type = 'REFUND'), 0) as refunds
                        from transactions t
                        left join accounts a on a.id = t.account_id and a.workspace_id = t.workspace_id
                        left join credit_cards c on c.id = t.card_id and c.workspace_id = t.workspace_id
                        where t.workspace_id = ? and t.status = 'POSTED'
                          and t.type in ('INCOME', 'EXPENSE', 'REFUND')
                          and t.occurred_on between ? and ?
                          and ((a.status = 'ACTIVE' and a.included_in_total) or c.status = 'ACTIVE')
                        """,
                (rs, row) -> new CashFlow(money(rs, "income", currency), money(rs, "expense", currency),
                        money(rs, "refunds", currency)),
                workspaceId.value(), period.from(), period.to());
    }

    @Override
    public Set<DataSource> dataSources(WorkspaceId workspaceId, OverviewPeriod period) {
        List<String> sources = jdbc.queryForList("""
                        select distinct case when t.source = 'MANUAL' then 'MANUAL' else b.format end as source
                        from transactions t
                        left join accounts a on a.id = t.account_id and a.workspace_id = t.workspace_id
                        left join credit_cards c on c.id = t.card_id and c.workspace_id = t.workspace_id
                        left join import_records r on r.transaction_id = t.id and r.workspace_id = t.workspace_id
                        left join import_batches b on b.id = r.batch_id and b.workspace_id = r.workspace_id
                        where t.workspace_id = ? and t.status = 'POSTED' and t.occurred_on between ? and ?
                          and (a.status = 'ACTIVE' or c.status = 'ACTIVE')
                        """,
                String.class, workspaceId.value(), period.from(), period.to());
        Set<DataSource> result = EnumSet.noneOf(DataSource.class);
        sources.stream().filter(Objects::nonNull).map(DataSource::valueOf).forEach(result::add);
        return result;
    }

    @Override
    public Activity activity(WorkspaceId workspaceId, OverviewPeriod period, Currency currency) {
        return jdbc.queryForObject("""
                        select exists (select 1 from transactions where workspace_id = ? and status = 'POSTED')
                                 as has_posted,
                               count(*) filter (where status = 'POSTED') as posted,
                               count(*) filter (where status = 'PENDING') as pending,
                               count(*) filter (where status = 'POSTED' and type = 'TRANSFER') as transfers,
                               coalesce(sum(amount) filter (where status = 'POSTED' and type = 'TRANSFER'), 0)
                                 as transfer_volume
                        from transactions
                        where workspace_id = ? and occurred_on between ? and ?
                        """,
                (rs, row) -> new Activity(rs.getBoolean("has_posted"), rs.getInt("posted"), rs.getInt("pending"),
                        rs.getInt("transfers"), money(rs, "transfer_volume", currency)),
                workspaceId.value(), workspaceId.value(), period.from(), period.to());
    }

    @Override
    public List<RecentMovement> recentMovements(WorkspaceId workspaceId, OverviewPeriod period, int limit,
                                                Currency currency) {
        return jdbc.query("""
                        select t.id, t.type, t.adjustment_direction, t.account_id, t.destination_account_id, t.amount,
                               t.occurred_on, t.description, t.status, t.refund_of_transaction_id, t.source,
                               c.id as card_id, c.nickname as card_nickname, c.last4 as card_last4
                        from transactions t
                        left join credit_cards c on c.id = coalesce(t.card_id, t.destination_card_id)
                          and c.workspace_id = t.workspace_id
                        where t.workspace_id = ? and t.status in ('POSTED', 'PENDING')
                          and t.occurred_on between ? and ?
                        order by t.occurred_on desc, t.created_at desc, t.id desc
                        limit ?
                        """,
                (rs, row) -> recentMovement(rs, currency),
                workspaceId.value(), period.from(), period.to(), limit);
    }

    private static RecentMovement recentMovement(ResultSet rs, Currency currency) throws SQLException {
        String direction = rs.getString("adjustment_direction");
        UUID account = rs.getObject("account_id", UUID.class);
        UUID destination = rs.getObject("destination_account_id", UUID.class);
        UUID refundOf = rs.getObject("refund_of_transaction_id", UUID.class);
        UUID card = rs.getObject("card_id", UUID.class);

        return new RecentMovement(
                new TransactionId(rs.getObject("id", UUID.class)),
                TransactionType.valueOf(rs.getString("type")),
                direction == null ? null : AdjustmentDirection.valueOf(direction),
                account == null ? null : new AccountId(account),
                Optional.ofNullable(destination).map(AccountId::new),
                money(rs, "amount", currency),
                rs.getObject("occurred_on", LocalDate.class),
                rs.getString("description"),
                TransactionStatus.valueOf(rs.getString("status")),
                Optional.ofNullable(refundOf).map(TransactionId::new),
                TransactionSource.valueOf(rs.getString("source")),
                card == null ? Optional.empty()
                        : Optional.of(new CardRef(card, rs.getString("card_nickname"), rs.getString("card_last4"))));
    }

    private static Money money(ResultSet rs, String column, Currency currency) throws SQLException {
        return new Money(rs.getBigDecimal(column), currency);
    }
}
