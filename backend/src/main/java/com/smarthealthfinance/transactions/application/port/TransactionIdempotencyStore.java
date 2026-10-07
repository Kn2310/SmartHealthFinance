package com.smarthealthfinance.transactions.application.port;

import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.transactions.domain.valueobject.IdempotencyKey;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionId;

import java.time.Instant;
import java.util.Optional;

/**
 * Registro de Idempotency-Key por Workspace (spec 05.6: guardar request hash).
 * O claim deve ocorrer na mesma transação de banco que grava o lançamento.
 */
public interface TransactionIdempotencyStore {

    Optional<Entry> find(WorkspaceId workspaceId, IdempotencyKey key);

    /**
     * Reserva a chave de forma atômica.
     *
     * @return false se a chave já pertence a outra requisição (inclusive uma concorrente que acabou de commitar)
     */
    boolean claim(WorkspaceId workspaceId, IdempotencyKey key, String requestHash, TransactionId transactionId,
                  Instant now);

    record Entry(String requestHash, TransactionId transactionId) {
    }
}
