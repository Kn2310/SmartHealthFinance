package com.smarthealthfinance.goals.domain.enums;

/** Por que não há previsão (ADR-0034 §4, ADR-0036). */
public enum GoalForecastReason {
    /** Forecast sem snapshot ou sem histórico suficiente: só fatos (ADR-0030). */
    FORECAST_NOT_READY,
    /** Aporte planejado zero. */
    NO_PLAN,
    /** Pausado, concluído ou arquivado. */
    NOT_ACTIVE,
    /** A conclusão passaria de 50 anos. */
    TOO_FAR
}
