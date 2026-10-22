package com.smarthealthfinance.cards.domain.enums;

/** Status de uma parcela (ADR-0025 §6), derivado do estado da fatura e dos estornos. */
public enum InstallmentStatus {
    /** Na fatura paga. */
    PAID,
    /** Na fatura aberta, ou fechada e ainda não quitada. */
    CURRENT,
    /** Numa fatura que ainda não começou. */
    FUTURE,
    /** Consumida inteira por estorno. */
    CANCELLED
}
