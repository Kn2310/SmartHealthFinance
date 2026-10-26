package com.smarthealthfinance.ingestion.application;

import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.ingestion.application.port.ImportedTransactionKeys;
import com.smarthealthfinance.ingestion.domain.valueobject.DedupeKey;
import com.smarthealthfinance.ingestion.domain.valueobject.ImportBatchId;
import com.smarthealthfinance.ingestion.domain.valueobject.ImportDestination;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionId;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Mesma semântica do adapter: chave única por (Workspace, conta ou cartão); a primeira reserva vence. */
public final class InMemoryImportedTransactionKeys implements ImportedTransactionKeys {

    private final Map<Scope, TransactionId> keys = new HashMap<>();

    private record Scope(WorkspaceId workspaceId, ImportDestination destination, DedupeKey key) {
    }

    public int size() {
        return keys.size();
    }

    @Override
    public Map<DedupeKey, TransactionId> findExisting(WorkspaceId workspaceId, ImportDestination destination,
                                                      Collection<DedupeKey> wanted) {
        Map<DedupeKey, TransactionId> existing = new HashMap<>();
        wanted.forEach(key -> {
            TransactionId id = keys.get(new Scope(workspaceId, destination, key));
            if (id != null) {
                existing.put(key, id);
            }
        });
        return existing;
    }

    @Override
    public Set<DedupeKey> claim(WorkspaceId workspaceId, ImportDestination destination, ImportBatchId batchId,
                                List<Claim> claims) {
        Set<DedupeKey> claimed = new HashSet<>();
        claims.forEach(claim -> {
            if (keys.putIfAbsent(new Scope(workspaceId, destination, claim.key()), claim.transactionId()) == null) {
                claimed.add(claim.key());
            }
        });
        return claimed;
    }

    @Override
    public void reassign(WorkspaceId workspaceId, ImportDestination destination,
                         Map<DedupeKey, TransactionId> targets) {
        targets.forEach((key, transaction) -> keys.replace(new Scope(workspaceId, destination, key), transaction));
    }
}
