package com.smarthealthfinance.ingestion.domain.repository;

import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.ingestion.domain.enums.RecordStatus;
import com.smarthealthfinance.ingestion.domain.model.ImportRecord;
import com.smarthealthfinance.ingestion.domain.valueobject.ImportBatchId;

import java.util.List;

/** Linhas de um batch, sempre escopadas pelo Workspace. */
public interface ImportRecordRepository {

    void addAll(WorkspaceId workspaceId, List<ImportRecord> records);

    /** Em ordem de linha; {@code status} nulo traz todas. */
    ImportRecordPage search(WorkspaceId workspaceId, ImportBatchId batchId, RecordStatus status, int page,
                            int pageSize);

    /** Todas as linhas com o status, em ordem de linha (processamento). */
    List<ImportRecord> findAll(WorkspaceId workspaceId, ImportBatchId batchId, RecordStatus status);

    /** Grava o novo status e a transação de cada linha processada. */
    void saveResults(WorkspaceId workspaceId, List<ImportRecord> records);

    /** @return quantas linhas foram removidas */
    int deleteAll(WorkspaceId workspaceId, ImportBatchId batchId);

    record ImportRecordPage(List<ImportRecord> items, long totalItems) {

        public ImportRecordPage {
            items = List.copyOf(items);
        }
    }
}
