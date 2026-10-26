package com.smarthealthfinance.ingestion.application.dto;

import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.ingestion.domain.enums.ImportFormat;
import com.smarthealthfinance.ingestion.domain.enums.ImportStatus;
import com.smarthealthfinance.ingestion.domain.model.ImportBatch;
import com.smarthealthfinance.ingestion.domain.model.ParsedStatement.StatementClosing;
import com.smarthealthfinance.ingestion.domain.valueobject.ImportDestination;
import com.smarthealthfinance.shared.domain.Money;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * @param accountId              conta de destino; nula num batch de cartão
 * @param cardId                 cartão de destino (ADR-0009 §19); nulo num batch de conta
 * @param skippedLines           pagamentos no arquivo do cartão, que não viram transação (§23)
 * @param reconciledLines        parcelas conciliadas com compras já lançadas (§26)
 * @param categorizedLines       das importadas, as que as regras categorizaram ("Categorizamos X de Y")
 * @param statementDueDate       vencimento informado no arquivo (OFX {@code CCCLOSING}), ou nulo
 * @param statementMinimumPayment pagamento mínimo informado no arquivo, ou nulo
 * @param sameFileImportedBefore o mesmo arquivo já foi importado neste destino (as linhas vêm como duplicadas)
 * @param previewExpiresAt       até quando o preview pode ser confirmado; nulo fora de PREVIEW
 */
public record ImportBatchView(UUID id, UUID workspaceId, UUID accountId, UUID cardId, ImportFormat format,
                              ImportStatus status, String fileName, long fileSize, int totalLines, int validLines,
                              int invalidLines, int duplicateLines, int skippedLines, int importedLines,
                              int reconciledLines, int categorizedLines, LocalDate firstDate, LocalDate lastDate,
                              LocalDate statementDueDate, Money statementMinimumPayment,
                              boolean sameFileImportedBefore, String failureReason, Instant createdAt,
                              Instant previewExpiresAt, Instant confirmedAt, Instant completedAt) {

    public static ImportBatchView from(ImportBatch batch, boolean sameFileImportedBefore) {
        ImportDestination destination = batch.destination();
        return new ImportBatchView(batch.id().value(), batch.workspaceId().value(),
                destination.accountId().map(AccountId::value).orElse(null), destination.cardId().orElse(null),
                batch.format(), batch.status(), batch.fileName(), batch.fileSize(),
                batch.totalLines(), batch.validLines(), batch.invalidLines(), batch.duplicateLines(),
                batch.skippedLines(), batch.importedLines(), batch.reconciledLines(), batch.categorizedLines(),
                batch.firstDate().orElse(null), batch.lastDate().orElse(null),
                batch.closing().map(StatementClosing::dueDate).orElse(null),
                batch.closing().map(StatementClosing::minimumPayment).orElse(null), sameFileImportedBefore,
                batch.failureReason().orElse(null), batch.createdAt(), batch.previewExpiresAt().orElse(null),
                batch.confirmedAt().orElse(null), batch.completedAt().orElse(null));
    }
}
