package com.smarthealthfinance.cards.infrastructure.persistence.adapter;

import com.smarthealthfinance.cards.domain.model.CardInstallment;
import com.smarthealthfinance.cards.domain.repository.CardInstallmentRepository;
import com.smarthealthfinance.cards.domain.valueobject.CardId;
import com.smarthealthfinance.cards.domain.valueobject.CardInvoiceId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.Money;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

/** Parcelas das compras no cartão (ADR-0025 §6). Toda consulta filtra por Workspace. */
@Repository
public class JdbcCardInstallmentRepository implements CardInstallmentRepository {

    private static final String COLUMNS = """
            workspace_id, card_id, transaction_id, number, installment_count, purchased_on, amount, currency,
            invoice_id, rules_version
            """;

    private static final RowMapper<CardInstallment> MAPPER = (rs, row) -> new CardInstallment(
            new WorkspaceId(rs.getObject("workspace_id", UUID.class)),
            new CardId(rs.getObject("card_id", UUID.class)),
            rs.getObject("transaction_id", UUID.class),
            rs.getInt("number"),
            rs.getInt("installment_count"),
            rs.getObject("purchased_on", LocalDate.class),
            new Money(rs.getBigDecimal("amount"), Currency.getInstance(rs.getString("currency"))),
            new CardInvoiceId(rs.getObject("invoice_id", UUID.class)),
            rs.getString("rules_version"));

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public JdbcCardInstallmentRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public void addAll(List<CardInstallment> installments) {
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.batchUpdate("""
                insert into card_installments (workspace_id, card_id, transaction_id, number, installment_count,
                  purchased_on, amount, currency, invoice_id, rules_version, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", installments, installments.size(), (ps, installment) -> {
            ps.setObject(1, installment.workspaceId().value());
            ps.setObject(2, installment.cardId().value());
            ps.setObject(3, installment.transactionId());
            ps.setInt(4, installment.number());
            ps.setInt(5, installment.count());
            ps.setDate(6, Date.valueOf(installment.purchasedOn()));
            ps.setBigDecimal(7, installment.amount().amount());
            ps.setString(8, installment.amount().currency().getCurrencyCode());
            ps.setObject(9, installment.invoiceId().value());
            ps.setString(10, installment.rulesVersion());
            ps.setTimestamp(11, now);
            ps.setTimestamp(12, now);
        });
    }

    @Override
    public List<CardInstallment> findByTransaction(WorkspaceId workspaceId, UUID transactionId) {
        return jdbc.query("select " + COLUMNS + """
                 from card_installments
                 where workspace_id = ? and transaction_id = ?
                 order by number""", MAPPER, workspaceId.value(), transactionId);
    }

    @Override
    public List<CardInstallment> findByCard(WorkspaceId workspaceId, CardId cardId) {
        return jdbc.query("select " + COLUMNS + """
                 from card_installments
                 where workspace_id = ? and card_id = ?
                 order by purchased_on, transaction_id, number""", MAPPER, workspaceId.value(), cardId.value());
    }

    @Override
    public void updateInvoices(List<CardInstallment> installments) {
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.batchUpdate("""
                update card_installments set invoice_id = ?, rules_version = ?, updated_at = ?
                where workspace_id = ? and transaction_id = ? and number = ?""", installments, installments.size(),
                (ps, installment) -> {
                    ps.setObject(1, installment.invoiceId().value());
                    ps.setString(2, installment.rulesVersion());
                    ps.setTimestamp(3, now);
                    ps.setObject(4, installment.workspaceId().value());
                    ps.setObject(5, installment.transactionId());
                    ps.setInt(6, installment.number());
                });
    }
}
