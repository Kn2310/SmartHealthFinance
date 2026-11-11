package com.smarthealthfinance.copilot.domain.enums;

/** Tipos de item pseudonimizado (ADR-0039 §3) e o prefixo do pseudônimo. */
public enum PseudonymKind {
    ACCOUNT("conta"),
    CARD("cartao"),
    GOAL("objetivo"),
    INSIGHT("insight"),
    CATEGORY("categoria");

    private final String prefix;

    PseudonymKind(String prefix) {
        this.prefix = prefix;
    }

    public String prefix() {
        return prefix;
    }
}
