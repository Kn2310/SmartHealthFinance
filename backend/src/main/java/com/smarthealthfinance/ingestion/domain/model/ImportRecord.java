package com.smarthealthfinance.ingestion.domain.model;

import com.smarthealthfinance.ingestion.domain.enums.RecordStatus;
import com.smarthealthfinance.ingestion.domain.valueobject.DedupeKey;
import com.smarthealthfinance.ingestion.domain.valueobject.ImportBatchId;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionId;

import java.util.Objects;

/**
 * Uma linha do arquivo dentro de um batch: lineage da transação até a origem (05.4, ADR-0009 §7/§11).
 * Linhas recusadas guardam só o motivo; as demais guardam o lançamento e a chave de deduplicação.
 *
 * @param line          nulo apenas em INVALID
 * @param dedupeKey     nulo apenas em INVALID
 * @param issue         presente apenas em INVALID
 * @param transactionId transação criada (IMPORTED) ou já existente (DUPLICATE), quando conhecida
 */
public record ImportRecord(ImportBatchId batchId, int lineNumber, RecordStatus status, StatementLine line,
                           DedupeKey dedupeKey, LineIssue issue, TransactionId transactionId) {

    public ImportRecord {
        Objects.requireNonNull(batchId, "batchId");
        Objects.requireNonNull(status, "status");
        if ((status == RecordStatus.INVALID) != (issue != null)) {
            throw new IllegalArgumentException("Só linhas INVALID têm motivo");
        }
        if (status != RecordStatus.INVALID && (line == null || dedupeKey == null)) {
            throw new IllegalArgumentException("Linha válida exige lançamento e chave");
        }
        if (status == RecordStatus.IMPORTED && transactionId == null) {
            throw new IllegalArgumentException("Linha importada exige a transação");
        }
    }

    public static ImportRecord invalid(ImportBatchId batchId, LineIssue issue) {
        return new ImportRecord(batchId, issue.lineNumber(), RecordStatus.INVALID, null, null, issue, null);
    }

    public static ImportRecord valid(ImportBatchId batchId, StatementLine line, DedupeKey key) {
        return new ImportRecord(batchId, line.lineNumber(), RecordStatus.VALID, line, key, null, null);
    }

    /** @param existing transação que já tem esta chave, se conhecida (nula para repetição dentro do arquivo) */
    public static ImportRecord duplicate(ImportBatchId batchId, StatementLine line, DedupeKey key,
                                         TransactionId existing) {
        return new ImportRecord(batchId, line.lineNumber(), RecordStatus.DUPLICATE, line, key, null, existing);
    }

    public ImportRecord imported(TransactionId created) {
        requireValid();
        return new ImportRecord(batchId, lineNumber, RecordStatus.IMPORTED, line, dedupeKey, null, created);
    }

    /** A chave foi reservada por outro batch entre o preview e o processamento. */
    public ImportRecord duplicateOf(TransactionId existing) {
        requireValid();
        return new ImportRecord(batchId, lineNumber, RecordStatus.DUPLICATE, line, dedupeKey, null, existing);
    }

    private void requireValid() {
        if (status != RecordStatus.VALID) {
            throw new IllegalStateException("Só linhas VALID são processadas");
        }
    }
}
