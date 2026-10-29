package com.smarthealthfinance.forecast.domain.enums;

/** Fase do previsto × realizado de um mês (ADR-0031 §6). */
public enum OutcomePhase {

    /** O mês ainda não fechou: nada é congelado. */
    CURRENT,
    /** Fechou e a data de negócio está até o dia 5 do mês seguinte: o realizado ainda é regravado. */
    RECONCILING,
    /** Depois do dia 5: o realizado e o erro ficam fixos. */
    SETTLED
}
