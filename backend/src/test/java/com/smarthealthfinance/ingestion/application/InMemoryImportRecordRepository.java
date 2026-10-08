package com.smarthealthfinance.ingestion.application;

import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.ingestion.domain.enums.RecordStatus;
import com.smarthealthfinance.ingestion.domain.model.ImportRecord;
import com.smarthealthfinance.ingestion.domain.repository.ImportRecordRepository;
import com.smarthealthfinance.ingestion.domain.valueobject.ImportBatchId;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryImportRecordRepository implements ImportRecordRepository {

    private final Map<Scope, TreeMap<Integer, ImportRecord>> records = new ConcurrentHashMap<>();

    private record Scope(WorkspaceId workspaceId, ImportBatchId batchId) {
    }

    public List<ImportRecord> all(WorkspaceId workspaceId, ImportBatchId batchId) {
        return List.copyOf(records.getOrDefault(new Scope(workspaceId, batchId), new TreeMap<>()).values());
    }

    @Override
    public void addAll(WorkspaceId workspaceId, List<ImportRecord> newRecords) {
        newRecords.forEach(record -> records.computeIfAbsent(new Scope(workspaceId, record.batchId()),
                scope -> new TreeMap<>()).put(record.lineNumber(), record));
    }

    @Override
    public ImportRecordPage search(WorkspaceId workspaceId, ImportBatchId batchId, RecordStatus status, int page,
                                   int pageSize) {
        List<ImportRecord> matching = all(workspaceId, batchId).stream()
                .filter(record -> status == null || record.status() == status)
                .sorted(Comparator.comparingInt(ImportRecord::lineNumber))
                .toList();
        return new ImportRecordPage(matching.stream().skip((long) page * pageSize).limit(pageSize).toList(),
                matching.size());
    }

    @Override
    public List<ImportRecord> findAll(WorkspaceId workspaceId, ImportBatchId batchId, RecordStatus status) {
        return search(workspaceId, batchId, status, 0, Integer.MAX_VALUE).items();
    }

    @Override
    public void saveResults(WorkspaceId workspaceId, List<ImportRecord> results) {
        addAll(workspaceId, results);
    }

    @Override
    public int deleteAll(WorkspaceId workspaceId, ImportBatchId batchId) {
        TreeMap<Integer, ImportRecord> removed = records.remove(new Scope(workspaceId, batchId));
        return removed == null ? 0 : removed.size();
    }
}
