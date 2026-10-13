package com.smarthealthfinance.transactions.domain.enums;

/**
 * Origem da categoria de uma transação (ADR-0013 §3). {@code MANUAL} é a escolha do usuário e nunca é sobrescrita
 * por regra automática; das automáticas, a regra aprendida do Workspace tem prioridade sobre a do sistema.
 */
public enum CategorySource {

    MANUAL,
    RULE_SYSTEM,
    RULE_LEARNED;

    /** Regra automática pode (re)classificar. */
    public boolean allowsAutomation() {
        return this != MANUAL;
    }
}
