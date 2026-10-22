package com.smarthealthfinance.cards.infrastructure.persistence.adapter;

import com.smarthealthfinance.cards.application.port.CardLedgerReader;
import com.smarthealthfinance.cards.domain.enums.InvoiceItemKind;
import com.smarthealthfinance.cards.domain.valueobject.CardId;
import com.smarthealthfinance.cards.domain.valueobject.CardInvoiceId;
import com.smarthealthfinance.identity.domain.model.Workspace;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.Money;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Somas por fatura sobre {@code card_installments} + {@code transactions} (ADR-0026 §7). É a reimplementação em SQL
 * do que a referência em memória do teste de paridade calcula; só transações {@code POSTED} contam.
 */
@Repository
public class JdbcCardLedgerReader implements CardLedgerReader {

    private static final String TOTALS = """
            select i.card_id, i.invoice_id, sum(i.amount - i.cancelled_amount) as charges
            from card_installments i
            join transactions t on t.id = i.transaction_id and t.workspace_id = i.workspace_id
            where i.workspace_id = ? and t.status = 'POSTED'
            """;

    private final JdbcTemplate jdbc;

    public JdbcCardLedgerReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public CardTotals totals(WorkspaceId workspaceId, CardId cardId) {
        Map<CardId, CardTotals> totals = query(TOTALS + " and i.card_id = ? group by i.card_id, i.invoice_id",
                workspaceId.value(), cardId.value());
        return totals.getOrDefault(cardId, empty());
    }

    @Override
    public Map<CardId, CardTotals> totalsByCard(WorkspaceId workspaceId) {
        return query(TOTALS + " group by i.card_id, i.invoice_id", workspaceId.value());
    }

    @Override
    public long itemCount(WorkspaceId workspaceId, CardId cardId, CardInvoiceId invoiceId) {
        Long total = jdbc.queryForObject("""
                select count(*)
                from card_installments i
                join transactions t on t.id = i.transaction_id and t.workspace_id = i.workspace_id
                where i.workspace_id = ? and i.card_id = ? and i.invoice_id = ? and t.status = 'POSTED'
                """, Long.class, workspaceId.value(), cardId.value(), invoiceId.value());
        return total == null ? 0 : total;
    }

    @Override
    public ItemPage items(WorkspaceId workspaceId, CardId cardId, CardInvoiceId invoiceId, int offset, int limit) {
        long total = itemCount(workspaceId, cardId, invoiceId);

        List<InvoiceItem> items = jdbc.query("""
                select t.id, t.occurred_on, t.description, t.category_code, t.merchant_name, t.source, i.number,
                       i.installment_count, i.amount, i.cancelled_amount
                from card_installments i
                join transactions t on t.id = i.transaction_id and t.workspace_id = i.workspace_id
                where i.workspace_id = ? and i.card_id = ? and i.invoice_id = ? and t.status = 'POSTED'
                order by t.occurred_on desc, i.number, t.id
                limit ? offset ?
                """, (rs, row) -> new InvoiceItem(InvoiceItemKind.CHARGE, rs.getObject("id", UUID.class),
                        rs.getObject("occurred_on", LocalDate.class), rs.getString("description"),
                        rs.getString("category_code"), rs.getString("merchant_name"), rs.getString("source"),
                        rs.getInt("number"), rs.getInt("installment_count"), money(rs.getBigDecimal("amount")),
                        money(rs.getBigDecimal("cancelled_amount"))),
                workspaceId.value(), cardId.value(), invoiceId.value(), limit, offset);

        return new ItemPage(items, total);
    }

    @Override
    public List<CommittedInstallment> installments(WorkspaceId workspaceId, CardId cardId,
                                                   Collection<CardInvoiceId> invoiceIds) {
        if (invoiceIds.isEmpty()) {
            return List.of();
        }
        Set<UUID> wanted = new HashSet<>();
        invoiceIds.forEach(id -> wanted.add(id.value()));

        return jdbc.query("""
                select i.invoice_id, t.id, t.description, i.number, i.installment_count,
                       i.amount - i.cancelled_amount as committed, i.purchased_on
                from card_installments i
                join transactions t on t.id = i.transaction_id and t.workspace_id = i.workspace_id
                where i.workspace_id = ? and i.card_id = ? and t.status = 'POSTED'
                  and i.amount > i.cancelled_amount
                order by i.purchased_on, t.id, i.number
                """, (rs, row) -> new CommittedInstallment(
                        new CardInvoiceId(rs.getObject("invoice_id", UUID.class)), rs.getObject("id", UUID.class),
                        rs.getString("description"), rs.getInt("number"), rs.getInt("installment_count"),
                        money(rs.getBigDecimal("committed"))),
                workspaceId.value(), cardId.value()).stream()
                .filter(installment -> wanted.contains(installment.invoiceId().value()))
                .toList();
    }

    private Map<CardId, CardTotals> query(String sql, Object... args) {
        Map<CardId, Map<CardInvoiceId, InvoiceTotals>> invoices = new LinkedHashMap<>();
        jdbc.query(sql, rs -> {
            CardId card = new CardId(rs.getObject("card_id", UUID.class));
            invoices.computeIfAbsent(card, ignored -> new HashMap<>())
                    .put(new CardInvoiceId(rs.getObject("invoice_id", UUID.class)),
                            new InvoiceTotals(money(rs.getBigDecimal("charges")), zero()));
        }, args);

        Map<CardId, CardTotals> totals = new LinkedHashMap<>();
        // Pagamentos entram na parte C (TRANSFER conta → cartão); até lá, zero.
        invoices.forEach((card, byInvoice) -> totals.put(card, new CardTotals(Map.copyOf(byInvoice), zero())));
        return totals;
    }

    private static CardTotals empty() {
        return new CardTotals(Map.of(), zero());
    }

    private static Money zero() {
        return Money.zero(Workspace.DEFAULT_BASE_CURRENCY);
    }

    private static Money money(BigDecimal amount) {
        return new Money(amount, Workspace.DEFAULT_BASE_CURRENCY);
    }
}
