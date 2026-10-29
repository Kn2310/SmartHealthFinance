package com.smarthealthfinance.forecast.domain.enums;

/**
 * Confiança de uma projeção ou série (ADR-0030 §2, §5). A ordem importa: do mais ao menos confiável; a confiança de
 * uma soma é a menor das partes estimadas.
 */
public enum Confidence {

    HIGH,
    MEDIUM,
    LOW;

    /** A menor (menos confiável) das duas. */
    public Confidence min(Confidence other) {
        return compareTo(other) >= 0 ? this : other;
    }

    /** No máximo {@code ceiling}. */
    public Confidence cap(Confidence ceiling) {
        return min(ceiling);
    }

    /** Desce um nível (LOW fica LOW). */
    public Confidence lower() {
        return this == HIGH ? MEDIUM : LOW;
    }
}
