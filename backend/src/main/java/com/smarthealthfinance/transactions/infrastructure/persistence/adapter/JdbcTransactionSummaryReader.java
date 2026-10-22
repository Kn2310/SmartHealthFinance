package com.smarthealthfinance.transactions.infrastructure.persistence.adapter;

import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.application.port.TransactionSummaryReader;
import com.smarthealthfinance.transactions.domain.repository.TransactionCriteria;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Locale;

/**
 * Resumo do período filtrado em uma consulta agregada (ADR-0016), com os mesmos predicados da listagem
 * ({@code JpaTransactionRepository}) e a semântica de fluxo do ADR-0006 §9 / {@code Transaction.cashFlowRole()}.
 * O teste de paridade compara com a referência em memória.
 */
@Repository
public class JdbcTransactionSummaryReader implements TransactionSummaryReader {

    private final JdbcTemplate jdbc;

    public JdbcTransactionSummaryReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public TransactionSummary summarize(WorkspaceId workspaceId, TransactionCriteria criteria, Currency currency) {
        List<Object> args = new ArrayList<>();

        // Quem conta no fluxo: a conta ou o cartão filtrado, ou as contas ativas incluídas no total e os cartões ativos
        // (como o Overview, ADR-0006 §17). O cartão é lido só pelo status, para a regra de arquivado.
        String inFlow;
        if (criteria.accountId() != null) {
            inFlow = "t.account_id = ?";
            args.add(criteria.accountId().value());
        }
        else if (criteria.cardId() != null) {
            inFlow = "t.card_id = ?";
            args.add(criteria.cardId().value());
        }
        else {
            inFlow = "((a.status = 'ACTIVE' and a.included_in_total) or c.status = 'ACTIVE')";
        }
        String posted = "t.status = 'POSTED'";

        StringBuilder sql = new StringBuilder("""
                select coalesce(sum(t.amount) filter (where %1$s and t.type = 'INCOME' and %2$s), 0) as income,
                       coalesce(sum(t.amount) filter (where %1$s and t.type = 'EXPENSE' and %2$s), 0) as expense,
                       coalesce(sum(t.amount) filter (where %1$s and t.type = 'REFUND' and %2$s), 0) as refunds,
                       count(*) filter (where %1$s and t.type = 'TRANSFER') as transfers,
                       coalesce(sum(t.amount) filter (where %1$s and t.type = 'TRANSFER'), 0) as transfer_volume,
                       count(*) filter (where %1$s) as posted,
                       count(*) filter (where t.status = 'PENDING') as pending
                from transactions t
                left join accounts a on a.id = t.account_id and a.workspace_id = t.workspace_id
                left join credit_cards c on c.id = t.card_id and c.workspace_id = t.workspace_id
                where t.workspace_id = ?
                """.formatted(posted, inFlow));
        // O %2$s aparece três vezes: os parâmetros dele também.
        List<Object> params = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            params.addAll(args);
        }
        params.add(workspaceId.value());

        if (criteria.from() != null) {
            sql.append(" and t.occurred_on >= ?");
            params.add(Date.valueOf(criteria.from()));
        }
        if (criteria.to() != null) {
            sql.append(" and t.occurred_on <= ?");
            params.add(Date.valueOf(criteria.to()));
        }
        if (criteria.type() != null) {
            sql.append(" and t.type = ?");
            params.add(criteria.type().name());
        }
        if (criteria.accountId() != null) {
            sql.append(" and (t.account_id = ? or t.destination_account_id = ?)");
            params.add(criteria.accountId().value());
            params.add(criteria.accountId().value());
        }
        if (criteria.cardId() != null) {
            sql.append(" and (t.card_id = ? or t.destination_card_id = ?)");
            params.add(criteria.cardId().value());
            params.add(criteria.cardId().value());
        }
        if (criteria.categoryCode() != null) {
            sql.append(" and t.category_code = ?");
            params.add(criteria.categoryCode().value());
        }
        if (criteria.uncategorized()) {
            sql.append(" and t.category_code is null");
        }
        if (criteria.text() != null) {
            sql.append(" and lower(t.description) like ? escape '\\'");
            params.add("%" + escapeLike(criteria.text().toLowerCase(Locale.ROOT)) + "%");
        }

        return jdbc.queryForObject(sql.toString(), (rs, row) -> new TransactionSummary(
                new Money(rs.getBigDecimal("income"), currency),
                new Money(rs.getBigDecimal("expense"), currency),
                new Money(rs.getBigDecimal("refunds"), currency),
                rs.getInt("transfers"),
                new Money(rs.getBigDecimal("transfer_volume"), currency),
                rs.getInt("posted"),
                rs.getInt("pending")), params.toArray());
    }

    /** O texto do usuário é literal: %, _ e o próprio escape não podem virar curingas. */
    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
