package com.smarthealthfinance.insights.domain.model;

import java.util.Objects;

/**
 * Linha do "O que mudou?" da Home (ADR-0023 §5): rótulo, valor e comparação, já formatados pelo detector com os
 * mesmos números do insight (ex.: "Alimentação fora" · "R$ 1.284,00 até 22 set" · "+32% vs média de abr–ago").
 */
public record ChangeLine(String label, String value, String detail) {

    public ChangeLine {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(detail, "detail");
    }
}
