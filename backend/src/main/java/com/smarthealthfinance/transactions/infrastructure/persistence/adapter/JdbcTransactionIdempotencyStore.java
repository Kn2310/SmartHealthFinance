package com.smarthealthfinance.transactions.infrastructure.persistence.adapter;

import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.transactions.application.port.TransactionIdempotencyStore;
import com.smarthealthfinance.transactions.domain.valueobject.IdempotencyKey;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC puro: o registro de chaves não é um agregado e não precisa de entidade JPA. O JdbcTemplate participa
 * da mesma transação/conexão do JpaTransactionManager.
 */
@Repository
public class JdbcTransactionIdempotencyStore implements TransactionIdempotencyStore {

    private final JdbcTemplate jdbc;

    public JdbcTransactionIdempotencyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Entry> find(WorkspaceId workspaceId, IdempotencyKey key) {
        return jdbc.query("""
                        select request_hash, transaction_id from transaction_idempotency_keys
                        where workspace_id = ? and idempotency_key = ?
                        """,
                (rs, row) -> new Entry(rs.getString("request_hash"),
                        new TransactionId(rs.getObject("transaction_id", UUID.class))),
                workspaceId.value(), key.value()).stream().findFirst();
    }

    /**
     * MANDATORY: o claim e o insert da transação precisam commitar juntos (FK adiada até o commit).
     * Sob concorrência, o ON CONFLICT espera o commit da outra requisição antes de decidir.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean claim(WorkspaceId workspaceId, IdempotencyKey key, String requestHash, TransactionId transactionId,
                         Instant now) {
        int inserted = jdbc.update("""
                        insert into transaction_idempotency_keys
                          (workspace_id, idempotency_key, request_hash, transaction_id, created_at)
                        values (?, ?, ?, ?, ?)
                        on conflict (workspace_id, idempotency_key) do nothing
                        """,
                workspaceId.value(), key.value(), requestHash, transactionId.value(), Timestamp.from(now));
        return inserted == 1;
    }
}
