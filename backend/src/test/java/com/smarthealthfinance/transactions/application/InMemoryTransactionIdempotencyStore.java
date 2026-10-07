package com.smarthealthfinance.transactions.application;

import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.transactions.application.port.TransactionIdempotencyStore;
import com.smarthealthfinance.transactions.domain.valueobject.IdempotencyKey;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionId;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Mesma semântica do adapter: chave única por Workspace; o primeiro claim vence. */
public final class InMemoryTransactionIdempotencyStore implements TransactionIdempotencyStore {

    private final Map<Scope, Entry> entries = new HashMap<>();

    public int size() {
        return entries.size();
    }

    @Override
    public Optional<Entry> find(WorkspaceId workspaceId, IdempotencyKey key) {
        return Optional.ofNullable(entries.get(new Scope(workspaceId, key)));
    }

    @Override
    public boolean claim(WorkspaceId workspaceId, IdempotencyKey key, String requestHash, TransactionId transactionId,
                         Instant now) {
        return entries.putIfAbsent(new Scope(workspaceId, key), new Entry(requestHash, transactionId)) == null;
    }

    private record Scope(WorkspaceId workspaceId, IdempotencyKey key) {
    }
}
