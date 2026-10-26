package com.smarthealthfinance.ingestion.presentation.dto.response;

import com.smarthealthfinance.ingestion.application.dto.ImportRecordView;
import com.smarthealthfinance.shared.presentation.money.MoneyDto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Linha do arquivo. Em INVALID só {@code lineNumber}, {@code status} e {@code issue} vêm preenchidos.
 *
 * @param amount        valor positivo; o sentido vem de {@code direction}
 * @param direction     {@code INFLOW} (vira receita; no cartão, crédito) ou {@code OUTFLOW} (vira despesa; no
 *                      cartão, compra)
 * @param transactionId transação criada (IMPORTED), compra conciliada (RECONCILED) ou já existente (DUPLICATE)
 * @param skipReason    só em SKIPPED: {@code CARD_PAYMENT}, o pagamento é registrado pela conta (ADR-0009 §23)
 * @param installment   parcela k de n reconhecida numa fatura (§24), ou nulo
 */
public record ImportRecordResponse(int lineNumber, String status, LocalDate occurredOn, MoneyDto amount,
                                   String direction, String description, Issue issue, UUID transactionId,
                                   String skipReason, Installment installment) {

    public record Issue(String field, String code) {
    }

    public record Installment(int number, int count) {
    }

    public static ImportRecordResponse from(ImportRecordView view) {
        return new ImportRecordResponse(view.lineNumber(), view.status().name(), view.occurredOn(),
                view.amount() == null ? null : MoneyDto.of(view.amount(), view.currency()),
                view.inflow() == null ? null : view.inflow() ? "INFLOW" : "OUTFLOW", view.description(),
                view.issueCode() == null ? null : new Issue(view.issueField(), view.issueCode()),
                view.transactionId(), view.skipReason(),
                view.installmentNumber() == null ? null
                        : new Installment(view.installmentNumber(), view.installmentCount()));
    }
}
