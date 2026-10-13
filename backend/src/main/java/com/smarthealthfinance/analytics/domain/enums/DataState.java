package com.smarthealthfinance.analytics.domain.enums;

/** Há dados para analisar? (ADR-0015 §4). */
public enum DataState {

    /** O Workspace não tem nenhuma movimentação que entre na análise. */
    NO_DATA,
    /** Há histórico, mas nada no mês analisado. */
    NO_ACTIVITY_IN_PERIOD,
    READY
}
