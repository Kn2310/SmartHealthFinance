package com.smarthealthfinance.ingestion.application.usecase;

import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.ingestion.domain.model.ImportBatch;
import com.smarthealthfinance.ingestion.domain.repository.ImportBatchRepository;
import com.smarthealthfinance.ingestion.domain.valueobject.ImportBatchId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * Encerra como FAILED um batch cujo processamento esgotou as tentativas (a mensagem segue para a DLQ). Sem isso o
 * usuário veria "processando" para sempre. Nenhuma transação foi criada: o processamento é tudo-ou-nada.
 */
@Service
public class FailImport {

    public static final String PROCESSING_ERROR = "PROCESSING_ERROR";

    private static final Logger log = LoggerFactory.getLogger(FailImport.class);

    private final ImportBatchRepository batches;
    private final Clock clock;

    public FailImport(ImportBatchRepository batches, Clock clock) {
        this.batches = batches;
        this.clock = clock;
    }

    @Transactional
    public void execute(WorkspaceId workspace, ImportBatchId importId, String reason) {
        batches.findByIdForUpdate(workspace, importId)
                .filter(batch -> batch.status().awaitsProcessing())
                .ifPresent(batch -> fail(batch, reason));
    }

    private void fail(ImportBatch batch, String reason) {
        batch.fail(reason, clock.instant());
        batches.save(batch);
        log.error("Import failed importId={} workspaceId={} reason={}", batch.id(), batch.workspaceId(), reason);
    }
}
