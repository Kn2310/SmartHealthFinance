package com.smarthealthfinance.analytics.domain.enums;

/** Freshness da projeção de Analytics de um Workspace (ADR-0014 §6). */
public enum ProjectionState {

    /** Todos os eventos do Workspace já estão refletidos. */
    UP_TO_DATE,
    /** Há eventos do Workspace ainda não processados: os números podem estar atrasados. */
    UPDATING,
    /** Há transações, mas a projeção ainda não foi construída (o rebuild vai rodar). */
    NOT_BUILT
}
