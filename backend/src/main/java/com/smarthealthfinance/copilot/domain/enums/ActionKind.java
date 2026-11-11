package com.smarthealthfinance.copilot.domain.enums;

/** Ações sugeridas (ADR-0039 §6): só links para telas existentes, nunca execução. */
public enum ActionKind {
    OPEN_INVOICE,
    OPEN_INSTALLMENTS,
    OPEN_HEALTH,
    OPEN_INSIGHT,
    OPEN_GOAL,
    OPEN_ANALYSIS_CATEGORY,
    OPEN_FORECAST;

    /** Ações que não apontam para um item (a tela é do Workspace inteiro). */
    public boolean needsTarget() {
        return this != OPEN_HEALTH && this != OPEN_FORECAST;
    }
}
