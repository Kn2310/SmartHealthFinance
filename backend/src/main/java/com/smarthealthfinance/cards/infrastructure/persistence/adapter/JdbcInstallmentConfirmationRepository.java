package com.smarthealthfinance.cards.infrastructure.persistence.adapter;

import com.smarthealthfinance.cards.domain.repository.InstallmentConfirmationRepository;
import com.smarthealthfinance.cards.domain.valueobject.CardId;
import com.smarthealthfinance.cards.domain.valueobject.InstallmentKey;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** {@code card_installment_confirmations} (V18). */
@Repository
public class JdbcInstallmentConfirmationRepository implements InstallmentConfirmationRepository {

    private final JdbcTemplate jdbc;

    public JdbcInstallmentConfirmationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Set<InstallmentKey> findByCard(WorkspaceId workspaceId, CardId cardId) {
        Set<InstallmentKey> keys = new HashSet<>();
        jdbc.query("select transaction_id, number from card_installment_confirmations"
                        + " where workspace_id = ? and card_id = ?",
                rs -> {
                    keys.add(new InstallmentKey(rs.getObject("transaction_id", UUID.class), rs.getInt("number")));
                }, workspaceId.value(), cardId.value());
        return keys;
    }

    @Override
    public void addAll(WorkspaceId workspaceId, CardId cardId, Collection<InstallmentKey> confirmed, Instant now) {
        if (confirmed.isEmpty()) {
            return;
        }
        List<InstallmentKey> keys = List.copyOf(confirmed);
        Timestamp createdAt = Timestamp.from(now);
        jdbc.batchUpdate("""
                        insert into card_installment_confirmations
                          (transaction_id, number, workspace_id, card_id, created_at)
                        values (?, ?, ?, ?, ?)
                        """,
                keys, keys.size(), (ps, key) -> {
                    ps.setObject(1, key.transactionId());
                    ps.setInt(2, key.number());
                    ps.setObject(3, workspaceId.value());
                    ps.setObject(4, cardId.value());
                    ps.setTimestamp(5, createdAt);
                });
    }
}
