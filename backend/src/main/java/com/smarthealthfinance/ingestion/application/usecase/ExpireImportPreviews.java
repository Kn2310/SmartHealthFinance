package com.smarthealthfinance.ingestion.application.usecase;

import com.smarthealthfinance.ingestion.domain.model.ImportBatch;
import com.smarthealthfinance.ingestion.domain.repository.ImportBatchRepository;
import com.smarthealthfinance.ingestion.domain.repository.ImportRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Previews não confirmados em 24h viram EXPIRED e perdem as linhas (ADR-0009 §16, minimização da 05.12).
 * Rodado por job, sem usuário: atravessa Workspaces, mas cada batch é relido com lock dentro do seu Workspace.
 */
@Service
public class ExpireImportPreviews {

    public static final int BATCH_LIMIT = 100;

    private static final Logger log = LoggerFactory.getLogger(ExpireImportPreviews.class);

    private final ImportBatchRepository batches;
    private final ImportRecordRepository records;
    private final Clock clock;

    public ExpireImportPreviews(ImportBatchRepository batches, ImportRecordRepository records, Clock clock) {
        this.batches = batches;
        this.records = records;
        this.clock = clock;
    }

    /** @return quantos previews expiraram nesta rodada (no máximo {@link #BATCH_LIMIT}) */
    @Transactional
    public int execute() {
        Instant now = clock.instant();
        int expired = 0;
        for (ImportBatch candidate : batches.findPreviewsCreatedUpTo(now.minus(ImportBatch.PREVIEW_TTL),
                BATCH_LIMIT)) {
            // Relido com lock: uma confirmação concorrente vence ou espera, nunca as duas.
            ImportBatch batch = batches.findByIdForUpdate(candidate.workspaceId(), candidate.id()).orElseThrow();
            if (batch.expire(now)) {
                batches.save(batch);
                records.deleteAll(batch.workspaceId(), batch.id());
                expired++;
            }
        }
        if (expired > 0) {
            log.info("Import previews expired count={}", expired);
        }
        return expired;
    }
}
