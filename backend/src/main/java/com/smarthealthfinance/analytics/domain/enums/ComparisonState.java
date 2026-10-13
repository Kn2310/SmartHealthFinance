package com.smarthealthfinance.analytics.domain.enums;

/** Quanto histórico sustenta a comparação com a média (ADR-0015 §4). */
public enum ComparisonState {

    /** Três meses ou mais. */
    AVAILABLE,
    /** Um ou dois meses: a UI pode avisar que a média é frágil. */
    LIMITED,
    /** Nenhum mês anterior com dados: sem variações. */
    INSUFFICIENT_DATA
}
