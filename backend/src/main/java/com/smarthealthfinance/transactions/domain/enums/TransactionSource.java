package com.smarthealthfinance.transactions.domain.enums;

/**
 * Origem do fato (spec 05.13: confirmed/imported separados). M2 só tem lançamento manual;
 * IMPORT (M4) e OPEN_FINANCE entram com nova migration ampliando o check do banco.
 * Estimativas e simulações nunca são transações: vivem em Forecast/Simulation.
 */
public enum TransactionSource {
    MANUAL
}
