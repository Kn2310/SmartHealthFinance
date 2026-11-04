package com.smarthealthfinance.forecast.domain.enums;

/** Estado do snapshot do Forecast de um Workspace (ADR-0031 §9). */
public enum ForecastFreshnessState {
    /** Há transações e nenhum snapshot na versão atual. */
    NOT_BUILT,
    /** Workspace marcado, eventos pendentes, Analytics em atualização ou snapshot de um dia anterior. */
    RECALCULATING,
    UP_TO_DATE
}
