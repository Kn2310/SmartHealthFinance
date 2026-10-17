package com.smarthealthfinance.insights.domain.enums;

/** Unidade de um número-chave ou de um ponto da série. */
public enum FigureKind {
    /** Valor na moeda base do Workspace. */
    MONEY,
    /** Percentual (32.0 = 32%). */
    PERCENT,
    COUNT,
    /** Meses de cobertura da reserva. */
    MONTHS,
    /** Pontos do score do Health (0–100). */
    POINTS,
    /** Múltiplo (4.1 = 4,1 vezes). */
    RATIO
}
