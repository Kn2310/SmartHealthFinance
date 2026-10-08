package com.smarthealthfinance.ingestion.application.port;

import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.ingestion.domain.valueobject.DedupeKey;
import com.smarthealthfinance.ingestion.domain.valueobject.ImportBatchId;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionId;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Chaves de deduplicação já importadas, por conta (ADR-0009 §11). A garantia vem do banco ({@code unique}),
 * não da aplicação.
 */
public interface ImportedTransactionKeys {

    /** Chaves já importadas nesta conta, com a transação de cada uma. */
    Map<DedupeKey, TransactionId> findExisting(WorkspaceId workspaceId, AccountId accountId,
                                               Collection<DedupeKey> keys);

    /**
     * Reserva as chaves para as transações que serão criadas, na transação de banco corrente. Uma reserva
     * concorrente da mesma chave espera o commit da outra antes de decidir.
     *
     * @return as chaves efetivamente reservadas; as demais já pertencem a outra transação
     */
    Set<DedupeKey> claim(WorkspaceId workspaceId, AccountId accountId, ImportBatchId batchId, List<Claim> claims);

    record Claim(DedupeKey key, TransactionId transactionId) {
    }
}
