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
    CARD_PAYMENTS,
    /** O número é uma projeção estimada do Forecast, com faixa e confiança; não é fato (D15, ADR-0032 §3). */
    ESTIMATED_FORECAST
}
