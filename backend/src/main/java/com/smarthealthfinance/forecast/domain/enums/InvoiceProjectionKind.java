package com.smarthealthfinance.forecast.domain.enums;

/** Qual fatura de um cartão está sendo projetada (ADR-0030 §3.5). */
public enum InvoiceProjectionKind {

    /** A fatura aberta: parcial (fato) + novas compras até o fechamento. */
    OPEN,
    /** A fatura seguinte à aberta. */
    NEXT,
    /** As demais até o fim do horizonte. */
    FUTURE
}
