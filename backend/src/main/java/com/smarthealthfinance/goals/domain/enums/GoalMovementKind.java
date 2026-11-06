package com.smarthealthfinance.goals.domain.enums;

/** Classificação de um movimento do destino (ADR-0034 §2.3). */
public enum GoalMovementKind {
    /** {@code TRANSFER} que entra no destino. */
    CONTRIBUTION,
    /** {@code TRANSFER} que sai do destino, para conta ou cartão. */
    WITHDRAWAL,
    /** Outros lançamentos do destino: mudam o saldo, mas não são aportes. */
    OTHER
}
