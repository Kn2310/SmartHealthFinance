package com.smarthealthfinance.ingestion.presentation.dto.response;

import com.smarthealthfinance.ingestion.application.dto.ImportRecordView;
import com.smarthealthfinance.shared.presentation.money.MoneyDto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Linha do arquivo. Em INVALID só {@code lineNumber}, {@code status} e {@code issue} vêm preenchidos.
 *
 * @param amount valor positivo; o sentido vem de {@code direction}
 * @param direction {@code INFLOW} (vira receita) ou {@code OUTFLOW} (vira despesa)
 */
public record ImportRecordResponse(int lineNumber, String status, LocalDate occurredOn, MoneyDto amount,
                                   String direction, String description, Issue issue, UUID transactionId) {

    public record Issue(String field, String code) {
    }

    public static ImportRecordResponse from(ImportRecordView view) {
        return new ImportRecordResponse(view.lineNumber(), view.status().name(), view.occurredOn(),
                view.amount() == null ? null : MoneyDto.of(view.amount(), view.currency()),
                view.inflow() == null ? null : view.inflow() ? "INFLOW" : "OUTFLOW", view.description(),
                view.issueCode() == null ? null : new Issue(view.issueField(), view.issueCode()),
                view.transactionId());
    }
}
