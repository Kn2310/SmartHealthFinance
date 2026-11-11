package com.smarthealthfinance.copilot.domain.enums;

/**
 * Intenções do MVP (ADR-0039 §4), enum versionado {@value #VERSION}. Mudar a lista exige nova versão do esquema de
 * intenção e do prompt.
 */
public enum Intent {
    OVERVIEW,
    CASH_FLOW,
    CATEGORY,
    MERCHANT,
    HEALTH,
    INSIGHTS,
    CARDS,
    FORECAST,
    GOALS,
    GOAL_SIMULATION,
    OUT_OF_SCOPE,
    UNSUPPORTED_ACTION;

    public static final String VERSION = "intents-v1";

    /** Respondidas sem dados e sem o modelo de resposta: texto fixo e, para ação, o link da tela. */
    public boolean answeredWithoutData() {
        return this == OUT_OF_SCOPE || this == UNSUPPORTED_ACTION;
    }
}
