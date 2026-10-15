package com.smarthealthfinance.health.domain.enums;

/** Situação de uma dimensão (ADR-0017 §2). Só {@code OK} tem nota e entra no score geral. */
public enum DimensionStatus {
    OK,
    /** Faltam dados para calcular (o motivo vem em {@link InsufficientReason}). */
    INSUFFICIENT_DATA,
    /** A dimensão ainda não existe no produto (Credit e Debt até o M8). */
    NOT_APPLICABLE
}
