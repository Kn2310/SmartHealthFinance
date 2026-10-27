package com.smarthealthfinance.insights.domain.enums;

/** Limitações explicadas no detalhe (ADR-0021 §7). */
public enum LimitationCode {
    PARTIAL_MONTH,
    SHORT_HISTORY,
    UNCATEGORIZED,
    ESTIMATED_SCORE,
    IMPORT_LAG,
    /** Fatura ainda aberta: o valor pode mudar até o fechamento (D11). */
    PARTIAL_INVOICE,
    /** Os números de cartão dependem dos pagamentos registrados ou importados (D12, D14). */
    CARD_PAYMENTS
}
