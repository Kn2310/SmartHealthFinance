package com.smarthealthfinance.ingestion.application.usecase;

import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.ingestion.application.dto.ImportBatchView;
import com.smarthealthfinance.ingestion.application.service.ImportAccess;
import com.smarthealthfinance.ingestion.domain.model.ImportBatch;
import com.smarthealthfinance.ingestion.domain.repository.ImportBatchRepository;
import com.smarthealthfinance.ingestion.domain.repository.ImportRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * Descarta um preview (PREVIEW → CANCELLED). As linhas são apagadas na hora: não viraram fato financeiro e não
 * há motivo para guardá-las (minimização, 05.12). Repetir não tem efeito.
 */
@Service
public class CancelImport {

    private static final Logger log = LoggerFactory.getLogger(CancelImport.class);

    private final ImportAccess access;
    private final ImportBatchRepository batches;
    private final ImportRecordRepository records;
    private final Clock clock;

    public CancelImport(ImportAccess access, ImportBatchRepository batches, ImportRecordRepository records,
                        Clock clock) {
        this.access = access;
        this.batches = batches;
        this.records = records;
        this.clock = clock;
    }

    @Transactional
    public ImportBatchView execute(UUID workspaceId, UUID importId) {
        WorkspaceId workspace = access.requireWorkspace(workspaceId);
        ImportBatch batch = access.requireBatchForUpdate(workspace, importId);

        if (batch.cancel(clock.instant())) {
            batches.save(batch);
            records.deleteAll(workspace, batch.id());
            log.info("Import cancelled importId={} workspaceId={}", batch.id(), workspace);
        }
        return ImportBatchView.from(batch, false);
    }
}
