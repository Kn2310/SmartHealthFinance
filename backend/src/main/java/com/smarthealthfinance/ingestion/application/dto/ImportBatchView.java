package com.smarthealthfinance.ingestion.application.dto;

import com.smarthealthfinance.ingestion.domain.enums.ImportFormat;
import com.smarthealthfinance.ingestion.domain.enums.ImportStatus;
import com.smarthealthfinance.ingestion.domain.model.ImportBatch;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * @param sameFileImportedBefore o mesmo arquivo já foi importado nesta conta (as linhas vêm como duplicadas)
 * @param previewExpiresAt       até quando o preview pode ser confirmado; nulo fora de PREVIEW
 */
public record ImportBatchView(UUID id, UUID workspaceId, UUID accountId, ImportFormat format, ImportStatus status,
                              String fileName, long fileSize, int totalLines, int validLines, int invalidLines,
                              int duplicateLines, int importedLines, LocalDate firstDate, LocalDate lastDate,
                              boolean sameFileImportedBefore, String failureReason, Instant createdAt,
                              Instant previewExpiresAt, Instant confirmedAt, Instant completedAt) {

    public static ImportBatchView from(ImportBatch batch, boolean sameFileImportedBefore) {
        return new ImportBatchView(batch.id().value(), batch.workspaceId().value(), batch.accountId().value(),
                batch.format(), batch.status(), batch.fileName(), batch.fileSize(), batch.totalLines(),
                batch.validLines(), batch.invalidLines(), batch.duplicateLines(), batch.importedLines(),
                batch.firstDate().orElse(null), batch.lastDate().orElse(null), sameFileImportedBefore,
                batch.failureReason().orElse(null), batch.createdAt(), batch.previewExpiresAt().orElse(null),
                batch.confirmedAt().orElse(null), batch.completedAt().orElse(null));
    }
}
