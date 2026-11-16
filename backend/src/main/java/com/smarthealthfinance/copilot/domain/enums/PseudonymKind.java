package com.smarthealthfinance.copilot.domain.enums;

/**
 * Tipos de item pseudonimizado (ADR-0039 §3) e o prefixo do pseudônimo. {@code MERCHANT}: todo comerciante
 * normalizado, inclusive destinatário de Pix/TED/DOC e MEI com nome de pessoa (adendo de 2026-11-16).
 */
public enum PseudonymKind {
    ACCOUNT("conta"),
    CARD("cartao"),
    GOAL("objetivo"),
    INSIGHT("insight"),
    CATEGORY("categoria"),
    MERCHANT("comerciante");

    private final String prefix;

    PseudonymKind(String prefix) {
        this.prefix = prefix;
    }

    public String prefix() {
        return prefix;
    }
}
