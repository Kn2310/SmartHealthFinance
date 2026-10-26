package com.smarthealthfinance.ingestion.presentation.dto.response;

import com.smarthealthfinance.ingestion.application.dto.ImportBatchView;
import com.smarthealthfinance.shared.presentation.money.MoneyDto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * @param accountId              conta de destino; nula num batch de cartão
 * @param cardId                 cartão de destino (ADR-0009 §19); nulo num batch de conta
 * @param period                 datas do primeiro e do último lançamento lido; nulo se nenhuma linha tem data válida
 * @param statement              vencimento e pagamento mínimo informados explicitamente no arquivo (§21), ou nulo
 * @param sameFileImportedBefore o mesmo arquivo já foi importado neste destino
 * @param failureReason          código estável, só em FAILED
 * @param previewExpiresAt       até quando o preview pode ser confirmado; só em PREVIEW
 */
public record ImportResponse(UUID id, UUID workspaceId, UUID accountId, UUID cardId, String format, String status,
                             String fileName, long fileSize, Lines lines, Period period, Statement statement,
                             boolean sameFileImportedBefore, String failureReason, Instant createdAt,
                             Instant previewExpiresAt, Instant confirmedAt, Instant completedAt) {

    /**
     * {@code total = valid + invalid + duplicate + skipped}; {@code imported}, {@code reconciled} (parcelas de compras
     * já lançadas no cartão) e {@code categorized} (das importadas, as que as regras categorizaram) só são preenchidos
     * depois do processamento. {@code skipped}: pagamentos no arquivo do cartão, que não viram transação.
     */
    public record Lines(int total, int valid, int invalid, int duplicate, int skipped, int imported, int reconciled,
                        int categorized) {
    }

    public record Period(LocalDate from, LocalDate to) {
    }

    /** Cada campo pode ser nulo: só o que o arquivo trouxe. */
    public record Statement(LocalDate dueDate, MoneyDto minimumPayment) {
    }

    public static ImportResponse from(ImportBatchView view) {
        boolean hasStatement = view.statementDueDate() != null || view.statementMinimumPayment() != null;
        return new ImportResponse(view.id(), view.workspaceId(), view.accountId(), view.cardId(), view.format().name(),
                view.status().name(), view.fileName(), view.fileSize(),
                new Lines(view.totalLines(), view.validLines(), view.invalidLines(), view.duplicateLines(),
                        view.skippedLines(), view.importedLines(), view.reconciledLines(), view.categorizedLines()),
                view.firstDate() == null ? null : new Period(view.firstDate(), view.lastDate()),
                hasStatement ? new Statement(view.statementDueDate(), view.statementMinimumPayment() == null ? null
                        : new MoneyDto(view.statementMinimumPayment().toPlainString(),
                                view.statementMinimumPayment().currency().getCurrencyCode())) : null,
                view.sameFileImportedBefore(), view.failureReason(), view.createdAt(), view.previewExpiresAt(),
                view.confirmedAt(), view.completedAt());
    }
}
