package com.smarthealthfinance.transactions.domain.enums;

/**
 * Origem do fato (spec 05.13: confirmed/imported separados). OPEN_FINANCE entra com nova migration ampliando o
 * check do banco. Estimativas e simulações nunca são transações: vivem em Forecast/Simulation.
 */
public enum TransactionSource {
    MANUAL,
    /** Lida de um extrato CSV/OFX (ADR-0009): nasce POSTED, sem transferência/reembolso inferidos. */
    IMPORT
}
