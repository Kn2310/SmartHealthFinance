package com.smarthealthfinance.ingestion.application.usecase;

import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.ingestion.application.dto.ImportPageViews;
import com.smarthealthfinance.ingestion.application.dto.ImportRecordView;
import com.smarthealthfinance.ingestion.application.service.ImportAccess;
import com.smarthealthfinance.ingestion.domain.enums.RecordStatus;
import com.smarthealthfinance.ingestion.domain.model.ImportBatch;
import com.smarthealthfinance.ingestion.domain.repository.ImportRecordRepository;
import com.smarthealthfinance.shared.domain.InvalidValueException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Linhas do preview/resultado em ordem de arquivo, com filtro opcional por status. */
@Service
public class ListImportRecords {

    public static final int DEFAULT_PAGE_SIZE = 50;
    public static final int MAX_PAGE_SIZE = 100;

    private final ImportAccess access;
    private final ImportRecordRepository records;

    public ListImportRecords(ImportAccess access, ImportRecordRepository records) {
        this.access = access;
        this.records = records;
    }

    @Transactional(readOnly = true)
    public ImportPageViews.RecordPage execute(UUID workspaceId, UUID importId, String status, Integer page,
                                              Integer pageSize) {
        WorkspaceId workspace = access.requireWorkspace(workspaceId);
        ImportBatch batch = access.requireBatch(workspace, importId);
        RecordStatus filter = parseStatus(status);
        int resolvedPage = ListImports.Paging.page(page);
        int resolvedSize = ListImports.Paging.pageSize(pageSize, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);

        ImportRecordRepository.ImportRecordPage result = records.search(workspace, batch.id(), filter, resolvedPage,
                resolvedSize);
        return new ImportPageViews.RecordPage(result.items().stream().map(ImportRecordView::from).toList(),
                resolvedPage, resolvedSize, result.totalItems());
    }

    private static RecordStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return RecordStatus.valueOf(status.strip());
        }
        catch (IllegalArgumentException invalid) {
            throw new InvalidValueException("status", "INVALID");
        }
    }
}
