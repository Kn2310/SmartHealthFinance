package com.smarthealthfinance.cards.domain.valueobject;

import com.smarthealthfinance.cards.domain.enums.InvoiceStatus;
import com.smarthealthfinance.shared.domain.Money;

/**
 * Situação de uma fatura calculada pelo razão (ADR-0025 §5/§7).
 *
 * @param amount    compras − créditos (pode ser negativo)
 * @param paid      parte do valor coberta por pagamentos ou crédito anterior
 * @param remaining quanto falta pagar (nunca negativo)
 */
public record InvoiceStatement(
        InvoiceDates dates,
        InvoiceStatus status,
        Money charges,
        Money credits,
        Money amount,
        Money paid,
        Money remaining
) {
}
