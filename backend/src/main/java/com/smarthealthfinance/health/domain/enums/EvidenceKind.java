package com.smarthealthfinance.health.domain.enums;

/** Unidade do valor de uma evidência. */
public enum EvidenceKind {
    /** Valor na moeda base do Workspace. */
    MONEY,
    /** Percentual (36.1 = 36,1%). */
    PERCENT,
    /** Meses de cobertura. */
    MONTHS,
    /** Contagem (meses, contas). */
    COUNT
}
