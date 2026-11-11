package com.smarthealthfinance.copilot.domain.enums;

/** Ações sugeridas (ADR-0039 §6): só links para telas existentes, nunca execução. */
public enum ActionKind {
    OPEN_INVOICE("Ver fatura"),
    OPEN_INSTALLMENTS("Ver parcelamentos"),
    OPEN_HEALTH("Ver saúde financeira"),
    OPEN_INSIGHT("Ver insight"),
    OPEN_GOAL("Ver objetivo"),
    OPEN_ANALYSIS_CATEGORY("Ver análise da categoria"),
    OPEN_FORECAST("Ver previsão");

    private final String label;

    ActionKind(String label) {
        this.label = label;
    }

    /** Texto fixo pt-BR do botão (ADR-0040 §3); a rota é montada pela UI. */
    public String label() {
        return label;
    }

    /** Ações que não apontam para um item (a tela é do Workspace inteiro). */
    public boolean needsTarget() {
        return this != OPEN_HEALTH && this != OPEN_FORECAST;
    }
}
