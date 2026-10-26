package com.smarthealthfinance.ingestion.application.port;

import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.ingestion.domain.valueobject.DedupeKey;
import com.smarthealthfinance.ingestion.domain.valueobject.ImportBatchId;
import com.smarthealthfinance.ingestion.domain.valueobject.ImportDestination;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionId;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Chaves de deduplicação já importadas, por conta ou por cartão (ADR-0009 §11 e §27). A garantia vem do banco
 * ({@code unique}), não da aplicação.
 */
public interface ImportedTransactionKeys {

    /** Chaves já importadas neste destino, com a transação de cada uma. */
    Map<DedupeKey, TransactionId> findExisting(WorkspaceId workspaceId, ImportDestination destination,
                                               Collection<DedupeKey> keys);

    /**
     * Reserva as chaves para as transações que serão criadas, na transação de banco corrente. Uma reserva
     * concorrente da mesma chave espera o commit da outra antes de decidir.
     *
     * @return as chaves efetivamente reservadas; as demais já pertencem a outra transação
     */
    Set<DedupeKey> claim(WorkspaceId workspaceId, ImportDestination destination, ImportBatchId batchId,
                         List<Claim> claims);

    /**
     * Aponta chaves já reservadas por este batch para outra transação: a parcela conciliada pertence a uma compra
     * existente (ADR-0009 §26/§27). Mesma transação de banco do claim.
     */
    void reassign(WorkspaceId workspaceId, ImportDestination destination, Map<DedupeKey, TransactionId> targets);

    record Claim(DedupeKey key, TransactionId transactionId) {
    }
}
