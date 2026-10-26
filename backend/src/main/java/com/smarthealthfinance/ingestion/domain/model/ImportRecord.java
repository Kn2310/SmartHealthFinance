package com.smarthealthfinance.ingestion.domain.model;

import com.smarthealthfinance.ingestion.domain.enums.RecordStatus;
import com.smarthealthfinance.ingestion.domain.valueobject.DedupeKey;
import com.smarthealthfinance.ingestion.domain.valueobject.ImportBatchId;
import com.smarthealthfinance.ingestion.domain.valueobject.InstallmentMark;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionId;

import java.util.Objects;

/**
 * Uma linha do arquivo dentro de um batch: lineage da transação até a origem (05.4, ADR-0009 §7/§11).
 * Linhas recusadas guardam só o motivo; as demais guardam o lançamento e a chave de deduplicação.
 *
 * @param line          nulo apenas em INVALID
 * @param dedupeKey     nulo apenas em INVALID
 * @param issue         presente apenas em INVALID
 * @param transactionId transação criada (IMPORTED), compra conciliada (RECONCILED) ou já existente (DUPLICATE),
 *                      quando conhecida
 * @param skipReason    presente apenas em SKIPPED (ex.: {@code CARD_PAYMENT}, ADR-0009 §23)
 * @param installment   parcela k/n reconhecida numa linha de fatura (§24), ou nulo
 */
public record ImportRecord(ImportBatchId batchId, int lineNumber, RecordStatus status, StatementLine line,
                           DedupeKey dedupeKey, LineIssue issue, TransactionId transactionId, String skipReason,
                           InstallmentMark installment) {

    public ImportRecord {
        Objects.requireNonNull(batchId, "batchId");
        Objects.requireNonNull(status, "status");
        if ((status == RecordStatus.INVALID) != (issue != null)) {
            throw new IllegalArgumentException("Só linhas INVALID têm motivo");
        }
        if ((status == RecordStatus.SKIPPED) != (skipReason != null)) {
            throw new IllegalArgumentException("Só linhas SKIPPED têm motivo de não importar");
        }
        if (status != RecordStatus.INVALID && (line == null || dedupeKey == null)) {
            throw new IllegalArgumentException("Linha válida exige lançamento e chave");
        }
        if ((status == RecordStatus.IMPORTED || status == RecordStatus.RECONCILED) && transactionId == null) {
            throw new IllegalArgumentException("Linha importada ou conciliada exige a transação");
        }
    }

    public ImportRecord(ImportBatchId batchId, int lineNumber, RecordStatus status, StatementLine line,
                        DedupeKey dedupeKey, LineIssue issue, TransactionId transactionId) {
        this(batchId, lineNumber, status, line, dedupeKey, issue, transactionId, null, null);
    }

    public static ImportRecord invalid(ImportBatchId batchId, LineIssue issue) {
        return new ImportRecord(batchId, issue.lineNumber(), RecordStatus.INVALID, null, null, issue, null);
    }

    public static ImportRecord valid(ImportBatchId batchId, StatementLine line, DedupeKey key) {
        return valid(batchId, line, key, null);
    }

    public static ImportRecord valid(ImportBatchId batchId, StatementLine line, DedupeKey key,
                                     InstallmentMark installment) {
        return new ImportRecord(batchId, line.lineNumber(), RecordStatus.VALID, line, key, null, null, null,
                installment);
    }

    /** @param existing transação que já tem esta chave, se conhecida (nula para repetição dentro do arquivo) */
    public static ImportRecord duplicate(ImportBatchId batchId, StatementLine line, DedupeKey key,
                                         TransactionId existing) {
        return duplicate(batchId, line, key, existing, null);
    }

    public static ImportRecord duplicate(ImportBatchId batchId, StatementLine line, DedupeKey key,
                                         TransactionId existing, InstallmentMark installment) {
        return new ImportRecord(batchId, line.lineNumber(), RecordStatus.DUPLICATE, line, key, null, existing, null,
                installment);
    }

    /** Lida e mostrada no preview, mas sem transação. */
    public static ImportRecord skipped(ImportBatchId batchId, StatementLine line, DedupeKey key, String reason) {
        Objects.requireNonNull(reason, "reason");
        return new ImportRecord(batchId, line.lineNumber(), RecordStatus.SKIPPED, line, key, null, null, reason, null);
    }

    public ImportRecord imported(TransactionId created) {
        requireValid();
        return new ImportRecord(batchId, lineNumber, RecordStatus.IMPORTED, line, dedupeKey, null, created, null,
                installment);
    }

    /** A parcela pertence a uma compra já lançada no cartão (ADR-0009 §26). */
    public ImportRecord reconciledWith(TransactionId purchase) {
        requireValid();
        return new ImportRecord(batchId, lineNumber, RecordStatus.RECONCILED, line, dedupeKey, null, purchase, null,
                installment);
    }

    /** A chave foi reservada por outro batch entre o preview e o processamento. */
    public ImportRecord duplicateOf(TransactionId existing) {
        requireValid();
        return new ImportRecord(batchId, lineNumber, RecordStatus.DUPLICATE, line, dedupeKey, null, existing, null,
                installment);
    }

    private void requireValid() {
        if (status != RecordStatus.VALID) {
            throw new IllegalStateException("Só linhas VALID são processadas");
        }
    }
}
