package com.smarthealthfinance.copilot.domain.enums;

/** Avisos da resposta (ADR-0040 §3), derivados do contexto e da validação; a UI escolhe o texto. */
public enum Notice {
    PARTIAL_PERIOD,
    ESTIMATED,
    SIMULATED,
    SAFE_MODE,
    UPDATING
}
