package com.smarthealthfinance.goals.domain.enums;

/** Atualização do Forecast em que a previsão se apoia (ADR-0031 §6, ADR-0036). */
public enum GoalForecastFreshness {
    UP_TO_DATE,
    RECALCULATING,
    NOT_BUILT
}
