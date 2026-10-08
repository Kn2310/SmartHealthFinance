package com.smarthealthfinance.ingestion.domain.repository;

import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.ingestion.domain.model.ImportBatch;
import com.smarthealthfinance.ingestion.domain.valueobject.ImportBatchId;
import com.smarthealthfinance.transactions.domain.valueobject.IdempotencyKey;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Toda consulta é escopada pelo Workspace: batch de outro Workspace é indistinguível de inexistente. */
public interface ImportBatchRepository {

    Optional<ImportBatch> findById(WorkspaceId workspaceId, ImportBatchId id);

    /** Bloqueia a linha até o fim da transação (serializa confirmações e entregas concorrentes). */
    Optional<ImportBatch> findByIdForUpdate(WorkspaceId workspaceId, ImportBatchId id);

    Optional<ImportBatch> findByIdempotencyKey(WorkspaceId workspaceId, IdempotencyKey key);

    /**
     * Insere o batch reservando a Idempotency-Key de forma atômica.
     *
     * @return false se a chave já pertence a outro batch (inclusive um concorrente que acabou de commitar)
     */
    boolean add(ImportBatch batch);

    /** @throws org.springframework.dao.OptimisticLockingFailureException se o batch mudou desde a leitura */
    void save(ImportBatch batch);

    /** Mais recentes primeiro. */
    ImportBatchPage search(WorkspaceId workspaceId, int page, int pageSize);

    /** Previews criados até {@code createdUpTo} (inclusive), de qualquer Workspace (job de expiração). */
    List<ImportBatch> findPreviewsCreatedUpTo(Instant createdUpTo, int limit);

    /** O mesmo arquivo (hash) já foi importado com sucesso nesta conta por outro batch. */
    boolean existsCompletedWithFile(WorkspaceId workspaceId, AccountId accountId, String fileSha256,
                                    ImportBatchId except);

    record ImportBatchPage(List<ImportBatch> items, long totalItems) {

        public ImportBatchPage {
            items = List.copyOf(items);
        }
    }
}
