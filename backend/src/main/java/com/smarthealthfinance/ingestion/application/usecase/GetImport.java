package com.smarthealthfinance.ingestion.application.usecase;

import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.ingestion.application.dto.ImportBatchView;
import com.smarthealthfinance.ingestion.application.service.ImportAccess;
import com.smarthealthfinance.ingestion.domain.model.ImportBatch;
import com.smarthealthfinance.ingestion.domain.repository.ImportBatchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Status e contagens de um batch: é o endpoint de polling do processamento (spec 05.6). */
@Service
public class GetImport {

    private final ImportAccess access;
    private final ImportBatchRepository batches;

    public GetImport(ImportAccess access, ImportBatchRepository batches) {
        this.access = access;
        this.batches = batches;
    }

    @Transactional(readOnly = true)
    public ImportBatchView execute(UUID workspaceId, UUID importId) {
        WorkspaceId workspace = access.requireWorkspace(workspaceId);
        ImportBatch batch = access.requireBatch(workspace, importId);
        return ImportBatchView.from(batch, batches.existsCompletedWithFile(workspace, batch.accountId(),
                batch.fileSha256(), batch.id()));
    }
}
