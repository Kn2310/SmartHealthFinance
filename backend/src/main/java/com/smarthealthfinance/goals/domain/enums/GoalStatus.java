package com.smarthealthfinance.goals.domain.enums;

/** Ciclo de vida do objetivo (ADR-0034 §1). {@code ARCHIVED} é terminal. */
public enum GoalStatus {
    ACTIVE,
    PAUSED,
    COMPLETED,
    ARCHIVED;

    /** Ativo ou pausado: ocupa o destino e entra no resumo da lista (ADR-0034 §2.1). */
    public boolean isOpen() {
        return this == ACTIVE || this == PAUSED;
    }
}
