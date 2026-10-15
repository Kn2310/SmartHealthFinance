package com.smarthealthfinance.overview.domain.enums;

/** De onde vieram os lançamentos de um período (ADR-0019): entrada manual ou o formato do extrato importado. */
public enum DataSource {
    MANUAL,
    CSV,
    OFX
}
