package com.smarthealthfinance.ingestion.application.dto;

import com.smarthealthfinance.ingestion.domain.enums.RecordStatus;
import com.smarthealthfinance.ingestion.domain.model.ImportRecord;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionId;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Linha do preview/resultado. Linhas INVALID trazem só a posição e o motivo.
 *
 * @param amount valor positivo, na escala de exibição da moeda (86.40)
 * @param inflow true para entrada (vira INCOME), false para saída (vira EXPENSE)
 */
public record ImportRecordView(int lineNumber, RecordStatus status, LocalDate occurredOn, BigDecimal amount,
                               String currency, Boolean inflow, String description, String issueField,
                               String issueCode, UUID transactionId) {

    public static ImportRecordView from(ImportRecord record) {
        UUID transactionId = Optional.ofNullable(record.transactionId()).map(TransactionId::value).orElse(null);
        if (record.line() == null) {
            return new ImportRecordView(record.lineNumber(), record.status(), null, null, null, null, null,
                    record.issue().field(), record.issue().code(), transactionId);
        }
        var line = record.line();
        return new ImportRecordView(record.lineNumber(), record.status(), line.occurredOn(),
                new BigDecimal(line.absoluteAmount().toPlainString()),
                line.signedAmount().currency().getCurrencyCode(), line.isInflow(), line.description(), null, null,
                transactionId);
    }
}
