package com.smarthealthfinance.ingestion.presentation.dto.response;

import com.smarthealthfinance.ingestion.application.dto.ImportBatchView;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * @param period                 datas do primeiro e do último lançamento lido; nulo se nenhuma linha tem data válida
 * @param sameFileImportedBefore o mesmo arquivo já foi importado nesta conta
 * @param failureReason          código estável, só em FAILED
 * @param previewExpiresAt       até quando o preview pode ser confirmado; só em PREVIEW
 */
public record ImportResponse(UUID id, UUID workspaceId, UUID accountId, String format, String status, String fileName,
                             long fileSize, Lines lines, Period period, boolean sameFileImportedBefore,
                             String failureReason, Instant createdAt, Instant previewExpiresAt,
                             Instant confirmedAt, Instant completedAt) {

    /** {@code total = valid + invalid + duplicate}; {@code imported} só é preenchido depois do processamento. */
    public record Lines(int total, int valid, int invalid, int duplicate, int imported) {
    }

    public record Period(LocalDate from, LocalDate to) {
    }

    public static ImportResponse from(ImportBatchView view) {
        return new ImportResponse(view.id(), view.workspaceId(), view.accountId(), view.format().name(),
                view.status().name(), view.fileName(), view.fileSize(),
                new Lines(view.totalLines(), view.validLines(), view.invalidLines(), view.duplicateLines(),
                        view.importedLines()),
                view.firstDate() == null ? null : new Period(view.firstDate(), view.lastDate()),
                view.sameFileImportedBefore(), view.failureReason(), view.createdAt(), view.previewExpiresAt(),
                view.confirmedAt(), view.completedAt());
    }
}
