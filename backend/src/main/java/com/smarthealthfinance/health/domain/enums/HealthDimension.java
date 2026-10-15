package com.smarthealthfinance.health.domain.enums;

/**
 * As seis dimensões do Financial Health (mvp.md), na ordem de exibição do layout, com o peso do score geral
 * (ADR-0017 §3). A soma dos pesos é 100.
 */
public enum HealthDimension {

    CASH_FLOW(25, "Fluxo de caixa"),
    SPENDING(15, "Gastos"),
    CREDIT(15, "Crédito"),
    DEBT(15, "Dívidas"),
    RESERVE(20, "Reserva"),
    CONSISTENCY(10, "Consistência");

    private final int weight;
    private final String label;

    HealthDimension(int weight, String label) {
        this.weight = weight;
        this.label = label;
    }

    public int weight() {
        return weight;
    }

    /** Nome em português, como no layout. */
    public String label() {
        return label;
    }
}
