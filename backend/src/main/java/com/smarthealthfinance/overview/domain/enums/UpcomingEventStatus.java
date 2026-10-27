package com.smarthealthfinance.overview.domain.enums;

/**
 * Situação do valor de um evento (ADR-0006 §18). Só fatos: nenhum valor estimado.
 */
public enum UpcomingEventStatus {
    /** Fatura aberta: o valor até agora, que pode mudar até o fechamento. */
    PARTIAL,
    /** Fatura fechada: o saldo em aberto. */
    CLOSED,
    /** Fatura vencida com saldo em aberto. */
    OVERDUE
}
