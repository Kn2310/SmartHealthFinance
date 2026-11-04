package com.smarthealthfinance.forecast.domain.enums;

/** Tipo de um próximo evento (ADR-0030 §3.3). Os três primeiros são fatos de fatura (ADR-0006 §18). */
public enum UpcomingEventKind {

    INVOICE_PARTIAL,
    INVOICE_OPEN,
    INVOICE_OVERDUE,
    RECURRING_INCOME,
    RECURRING_EXPENSE
}
