package com.smarthealthfinance.forecast.domain.model;

import com.smarthealthfinance.forecast.domain.enums.InvoiceProjectionKind;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;

/**
 * Fatura projetada de um cartão (ADR-0030 §3.5). {@code total.factPart} é o parcial (aberta) ou o comprometido
 * (futuras); {@code total.estimatedPart} são as novas compras pelo padrão. Sem histórico de cartão suficiente, o total
 * é só o fato, com {@code PARTIAL_CARD_HISTORY}.
 */
public record CardInvoiceProjection(Channel card, String nickname, YearMonth closingMonth, YearMonth dueMonth,
                                    LocalDate closingDate, LocalDate dueDate, InvoiceProjectionKind kind,
                                    Projected total) {

    public CardInvoiceProjection {
        Objects.requireNonNull(card, "card");
        Objects.requireNonNull(closingMonth, "closingMonth");
        Objects.requireNonNull(dueMonth, "dueMonth");
        Objects.requireNonNull(closingDate, "closingDate");
        Objects.requireNonNull(dueDate, "dueDate");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(total, "total");
    }
}
