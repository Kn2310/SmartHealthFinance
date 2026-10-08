package com.smarthealthfinance.ingestion.application;

import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.ingestion.application.port.ImportedTransactionKeys;
import com.smarthealthfinance.ingestion.domain.valueobject.DedupeKey;
import com.smarthealthfinance.ingestion.domain.valueobject.ImportBatchId;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionId;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Mesma semântica do adapter: chave única por (Workspace, conta); a primeira reserva vence. */
public final class InMemoryImportedTransactionKeys implements ImportedTransactionKeys {

    private final Map<Scope, TransactionId> keys = new HashMap<>();

    private record Scope(WorkspaceId workspaceId, AccountId accountId, DedupeKey key) {
    }

    public int size() {
        return keys.size();
    }

    @Override
    public Map<DedupeKey, TransactionId> findExisting(WorkspaceId workspaceId, AccountId accountId,
                                                      Collection<DedupeKey> wanted) {
        Map<DedupeKey, TransactionId> existing = new HashMap<>();
        wanted.forEach(key -> {
            TransactionId id = keys.get(new Scope(workspaceId, accountId, key));
            if (id != null) {
                existing.put(key, id);
            }
        });
        return existing;
    }

    @Override
    public Set<DedupeKey> claim(WorkspaceId workspaceId, AccountId accountId, ImportBatchId batchId,
                                List<Claim> claims) {
        Set<DedupeKey> claimed = new HashSet<>();
        claims.forEach(claim -> {
            if (keys.putIfAbsent(new Scope(workspaceId, accountId, claim.key()), claim.transactionId()) == null) {
                claimed.add(claim.key());
            }
        });
        return claimed;
    }
}
