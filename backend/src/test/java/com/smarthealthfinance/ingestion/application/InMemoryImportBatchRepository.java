package com.smarthealthfinance.ingestion.application;

import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.ingestion.domain.enums.ImportStatus;
import com.smarthealthfinance.ingestion.domain.model.ImportBatch;
import com.smarthealthfinance.ingestion.domain.repository.ImportBatchRepository;
import com.smarthealthfinance.ingestion.domain.valueobject.ImportBatchId;
import com.smarthealthfinance.transactions.domain.valueobject.IdempotencyKey;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Mesma semântica do adapter JDBC: escopo por Workspace, chave única por Workspace e lock otimista. */
public final class InMemoryImportBatchRepository implements ImportBatchRepository {

    private final Map<ImportBatchId, ImportBatch> batches = new LinkedHashMap<>();

    public ImportBatch stored(ImportBatchId id) {
        return copy(batches.get(id), batches.get(id).version());
    }

    public int size() {
        return batches.size();
    }

    @Override
    public Optional<ImportBatch> findById(WorkspaceId workspaceId, ImportBatchId id) {
        return Optional.ofNullable(batches.get(id)).filter(batch -> batch.workspaceId().equals(workspaceId))
                .map(batch -> copy(batch, batch.version()));
    }

    @Override
    public Optional<ImportBatch> findByIdForUpdate(WorkspaceId workspaceId, ImportBatchId id) {
        return findById(workspaceId, id);
    }

    @Override
    public Optional<ImportBatch> findByIdempotencyKey(WorkspaceId workspaceId, IdempotencyKey key) {
        return batches.values().stream()
                .filter(batch -> batch.workspaceId().equals(workspaceId) && batch.idempotencyKey().equals(key))
                .findFirst().map(batch -> copy(batch, batch.version()));
    }

    @Override
    public boolean add(ImportBatch batch) {
        if (findByIdempotencyKey(batch.workspaceId(), batch.idempotencyKey()).isPresent()) {
            return false;
        }
        batches.put(batch.id(), copy(batch, batch.version()));
        return true;
    }

    @Override
    public void save(ImportBatch batch) {
        ImportBatch current = batches.get(batch.id());
        if (current == null || current.version() != batch.version()) {
            throw new OptimisticLockingFailureException("stale " + batch.id());
        }
        batches.put(batch.id(), copy(batch, batch.version() + 1));
    }

    @Override
    public ImportBatchPage search(WorkspaceId workspaceId, int page, int pageSize) {
        List<ImportBatch> all = batches.values().stream()
                .filter(batch -> batch.workspaceId().equals(workspaceId))
                .sorted(Comparator.comparing(ImportBatch::createdAt).thenComparing(b -> b.id().value()).reversed())
                .toList();
        return new ImportBatchPage(all.stream().skip((long) page * pageSize).limit(pageSize)
                .map(batch -> copy(batch, batch.version())).toList(), all.size());
    }

    @Override
    public List<ImportBatch> findPreviewsCreatedUpTo(Instant createdUpTo, int limit) {
        return batches.values().stream()
                .filter(batch -> batch.status() == ImportStatus.PREVIEW && !batch.createdAt().isAfter(createdUpTo))
                .sorted(Comparator.comparing(ImportBatch::createdAt)).limit(limit)
                .map(batch -> copy(batch, batch.version())).toList();
    }

    @Override
    public boolean existsCompletedWithFile(WorkspaceId workspaceId, AccountId accountId, String fileSha256,
                                           ImportBatchId except) {
        return batches.values().stream().anyMatch(batch -> batch.workspaceId().equals(workspaceId)
                && batch.accountId().equals(accountId) && batch.fileSha256().equals(fileSha256)
                && batch.status() == ImportStatus.COMPLETED && !batch.id().equals(except));
    }

    private static ImportBatch copy(ImportBatch b, long version) {
        return ImportBatch.restore(b.id(), b.workspaceId(), b.accountId(), b.createdBy(), b.format(), b.status(),
                b.fileName(), b.fileSize(), b.fileSha256(), b.idempotencyKey(), b.requestHash(), b.totalLines(),
                b.validLines(), b.invalidLines(), b.duplicateLines(), b.importedLines(), b.firstDate().orElse(null),
                b.lastDate().orElse(null), b.failureReason().orElse(null), b.createdAt(), b.updatedAt(),
                b.confirmedAt().orElse(null), b.completedAt().orElse(null), version);
    }
}
