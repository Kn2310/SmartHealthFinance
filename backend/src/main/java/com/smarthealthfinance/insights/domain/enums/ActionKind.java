package com.smarthealthfinance.insights.domain.enums;

/**
 * Ações para telas que existem (ADR-0021 §2); o frontend monta a rota a partir do código e dos parâmetros.
 * {@code CREATE_SAVINGS_GOAL} (M10) e {@code ASK_COPILOT} (M11) saem sempre com {@code available = false}.
 */
public enum ActionKind {
    VIEW_TRANSACTIONS,
    VIEW_CATEGORY,
    VIEW_ANALYSIS,
    VIEW_MERCHANTS,
    VIEW_TRANSACTION,
    IMPORT_STATEMENT,
    VIEW_INCOME,
    VIEW_HEALTH,
    VIEW_ACCOUNTS,
    CATEGORIZE,
    CREATE_SAVINGS_GOAL,
    ASK_COPILOT
}
