package com.smarthealthfinance.ingestion.infrastructure.persistence.adapter;

import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.ingestion.application.port.ImportedTransactionKeys;
import com.smarthealthfinance.ingestion.domain.valueobject.DedupeKey;
import com.smarthealthfinance.ingestion.domain.valueobject.ImportBatchId;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** {@code imported_transaction_keys}: a PK (workspace, conta, chave) é a garantia de deduplicação. */
@Repository
public class JdbcImportedTransactionKeys implements ImportedTransactionKeys {

    /** Lista de parâmetros por consulta: o PK continua usado e o limite de parâmetros do driver fica longe. */
    private static final int LOOKUP_CHUNK = 1_000;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public JdbcImportedTransactionKeys(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public Map<DedupeKey, TransactionId> findExisting(WorkspaceId workspaceId, AccountId accountId,
                                                      Collection<DedupeKey> keys) {
        Map<DedupeKey, TransactionId> existing = new HashMap<>();
        List<DedupeKey> all = new ArrayList<>(keys);
        for (int from = 0; from < all.size(); from += LOOKUP_CHUNK) {
            List<DedupeKey> chunk = all.subList(from, Math.min(all.size(), from + LOOKUP_CHUNK));
            List<Object> args = new ArrayList<>(chunk.size() + 2);
            args.add(workspaceId.value());
            args.add(accountId.value());
            chunk.forEach(key -> args.add(key.value()));

            jdbc.query("select dedupe_key, transaction_id from imported_transaction_keys"
                            + " where workspace_id = ? and account_id = ? and dedupe_key in ("
                            + String.join(", ", Collections.nCopies(chunk.size(), "?")) + ")",
                    rs -> {
                        existing.put(new DedupeKey(rs.getString("dedupe_key")),
                                new TransactionId(rs.getObject("transaction_id", UUID.class)));
                    },
                    args.toArray());
        }
        return existing;
    }

    /**
     * MANDATORY: a reserva e as transações commitam juntas (FK adiada até o commit). Sob concorrência, o
     * {@code ON CONFLICT} espera o commit do outro batch antes de decidir.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Set<DedupeKey> claim(WorkspaceId workspaceId, AccountId accountId, ImportBatchId batchId,
                                List<Claim> claims) {
        if (claims.isEmpty()) {
            return Set.of();
        }
        Timestamp now = Timestamp.from(clock.instant());
        int[][] results = jdbc.batchUpdate("""
                        insert into imported_transaction_keys
                          (workspace_id, account_id, dedupe_key, transaction_id, batch_id, created_at)
                        values (?, ?, ?, ?, ?, ?)
                        on conflict (workspace_id, account_id, dedupe_key) do nothing
                        """,
                claims, claims.size(), (ps, claim) -> {
                    ps.setObject(1, workspaceId.value());
                    ps.setObject(2, accountId.value());
                    ps.setString(3, claim.key().value());
                    ps.setObject(4, claim.transactionId().value());
                    ps.setObject(5, batchId.value());
                    ps.setTimestamp(6, now);
                });

        Set<DedupeKey> claimed = new HashSet<>();
        int[] counts = results[0];
        for (int i = 0; i < counts.length; i++) {
            // Sem a contagem por linha (ex.: reWriteBatchedInserts) não dá para saber quem venceu a reserva.
            if (counts[i] == Statement.SUCCESS_NO_INFO) {
                throw new IllegalStateException("O driver JDBC não informou o resultado de cada reserva");
            }
            if (counts[i] == 1) {
                claimed.add(claims.get(i).key());
            }
        }
        return claimed;
    }
}
