package com.smarthealthfinance.insights.domain.enums;

/** Ciclo de vida (ADR-0021 §5). Só {@code ACTIVE} aparece na lista; "Novo" = {@code ACTIVE} sem {@code seenAt}. */
public enum InsightStatus {
    ACTIVE,
    /** A condição deixou de valer num recálculo do mesmo período; volta a {@code ACTIVE} se valer de novo. */
    RESOLVED,
    EXPIRED,
    /** Ação do usuário, definitiva: o fingerprint nunca volta, em nenhuma versão. */
    DISMISSED
}
