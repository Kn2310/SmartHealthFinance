package com.smarthealthfinance.health.domain.enums;

/** Quão atual está o snapshot do Health de um Workspace (ADR-0018 §7). */
public enum HealthFreshnessState {
    UP_TO_DATE,
    /** Workspace marcado, eventos pendentes, Analytics atualizando ou snapshot de um dia anterior. */
    RECALCULATING,
    /** Há transações e nenhum snapshot na versão atual. */
    NOT_BUILT
}
