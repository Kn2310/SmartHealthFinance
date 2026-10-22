package com.smarthealthfinance.cards.domain.enums;

/** Estado da fatura (ADR-0025 §5), sempre derivado de "hoje", das datas, do valor e do que foi pago. */
public enum InvoiceStatus {
    /** O período ainda não começou: só parcelas contratadas. */
    FUTURE,
    /** Período em curso: parcial, pode mudar até o fechamento. */
    OPEN,
    /** Fechada, não vencida, nada pago. */
    CLOSED,
    /** Fechada, não vencida, pago menos que o valor. */
    PARTIALLY_PAID,
    /** Fechada e quitada (ou sem valor a pagar). */
    PAID,
    /** Passou do vencimento efetivo com valor em aberto. */
    OVERDUE
}
