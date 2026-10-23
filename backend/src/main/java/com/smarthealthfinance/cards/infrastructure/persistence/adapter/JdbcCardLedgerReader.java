package com.smarthealthfinance.cards.infrastructure.persistence.adapter;

import com.smarthealthfinance.cards.application.port.CardLedgerReader;
import com.smarthealthfinance.cards.domain.valueobject.CardId;
import com.smarthealthfinance.cards.domain.valueobject.CardInvoiceId;
import com.smarthealthfinance.identity.domain.model.Workspace;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.Money;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Fatos de cartão sobre {@code card_installments} e {@code transactions} (ADR-0026 §7 e §10). Só transações
 * {@code POSTED} contam; toda consulta filtra por Workspace. O teste de paridade compara estas consultas com a
 * referência em memória sobre os mesmos fatos.
 */
@Repository
public class JdbcCardLedgerReader implements CardLedgerReader {

    private static final String INSTALLMENTS = """
            select i.card_id, i.transaction_id, i.number, i.installment_count, i.purchased_on, i.amount, i.invoice_id,
                   t.description, t.category_code, t.merchant_name, t.source
            from card_installments i
            join transactions t on t.id = i.transaction_id and t.workspace_id = i.workspace_id
            where i.workspace_id = ? and t.status = 'POSTED'
            """;

    private static final String CREDITS = """
            select t.card_id, t.id, t.occurred_on, t.amount, t.refund_of_transaction_id, t.description, t.category_code,
                   t.merchant_name, t.source
            from transactions t
            where t.workspace_id = ? and t.status = 'POSTED' and t.type = 'REFUND' and t.card_id is not null
            """;

    private static final String PAYMENTS = """
            select t.destination_card_id as card_id, t.occurred_on, sum(t.amount) as payments
            from transactions t
            where t.workspace_id = ? and t.status = 'POSTED' and t.type = 'TRANSFER' and t.destination_card_id is not null
            """;

    private final JdbcTemplate jdbc;

    public JdbcCardLedgerReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public CardFacts facts(WorkspaceId workspaceId, CardId cardId) {
        Map<CardId, CardFacts> facts = query(workspaceId, cardId);
        return facts.getOrDefault(cardId, new CardFacts(List.of(), List.of(), List.of()));
    }

    @Override
    public Map<CardId, CardFacts> factsByCard(WorkspaceId workspaceId) {
        return query(workspaceId, null);
    }

    private Map<CardId, CardFacts> query(WorkspaceId workspaceId, CardId card) {
        Object[] args = card == null ? new Object[] {workspaceId.value()}
                : new Object[] {workspaceId.value(), card.value()};

        Map<CardId, List<InstallmentFact>> installments = new LinkedHashMap<>();
        jdbc.query(INSTALLMENTS + (card == null ? "" : " and i.card_id = ?")
                + " order by i.purchased_on, i.transaction_id, i.number", rs -> {
                    installments.computeIfAbsent(new CardId(rs.getObject("card_id", UUID.class)),
                            ignored -> new ArrayList<>()).add(new InstallmentFact(
                            rs.getObject("transaction_id", UUID.class), rs.getInt("number"),
                            rs.getInt("installment_count"), rs.getObject("purchased_on", LocalDate.class),
                            money(rs.getBigDecimal("amount")), new CardInvoiceId(rs.getObject("invoice_id", UUID.class)),
                            rs.getString("description"), rs.getString("category_code"), rs.getString("merchant_name"),
                            rs.getString("source")));
                }, args);

        Map<CardId, List<CreditFact>> credits = new HashMap<>();
        jdbc.query(CREDITS + (card == null ? "" : " and t.card_id = ?") + " order by t.occurred_on, t.id", rs -> {
            credits.computeIfAbsent(new CardId(rs.getObject("card_id", UUID.class)), ignored -> new ArrayList<>())
                    .add(new CreditFact(rs.getObject("id", UUID.class), rs.getObject("occurred_on", LocalDate.class),
                            money(rs.getBigDecimal("amount")), rs.getObject("refund_of_transaction_id", UUID.class),
                            rs.getString("description"), rs.getString("category_code"),
                            rs.getString("merchant_name"), rs.getString("source")));
        }, args);

        Map<CardId, List<PaymentFact>> payments = new HashMap<>();
        jdbc.query(PAYMENTS + (card == null ? "" : " and t.destination_card_id = ?")
                + " group by t.destination_card_id, t.occurred_on order by t.occurred_on", rs -> {
                    payments.computeIfAbsent(new CardId(rs.getObject("card_id", UUID.class)), ignored -> new ArrayList<>())
                            .add(new PaymentFact(rs.getObject("occurred_on", LocalDate.class),
                                    money(rs.getBigDecimal("payments"))));
                }, args);

        Map<CardId, CardFacts> facts = new LinkedHashMap<>();
        for (CardId id : union(installments.keySet(), credits.keySet(), payments.keySet())) {
            facts.put(id, new CardFacts(List.copyOf(installments.getOrDefault(id, List.of())),
                    List.copyOf(credits.getOrDefault(id, List.of())),
                    List.copyOf(payments.getOrDefault(id, List.of()))));
        }
        return facts;
    }

    @SafeVarargs
    private static List<CardId> union(Set<CardId>... sets) {
        List<CardId> all = new ArrayList<>();
        for (Set<CardId> set : sets) {
            set.stream().filter(id -> !all.contains(id)).forEach(all::add);
        }
        return all;
    }

    private static Money money(BigDecimal amount) {
        return new Money(amount, Workspace.DEFAULT_BASE_CURRENCY);
    }
}
