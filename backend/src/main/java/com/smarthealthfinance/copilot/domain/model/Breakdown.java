package com.smarthealthfinance.copilot.domain.model;

import java.util.List;
import java.util.Objects;

/**
 * Quadro montado pelo sistema a partir do que a tool já decompõe (ADR-0040 §3): fatores da diferença da fatura ou
 * dimensões da saúde. Nunca vem do modelo. Linhas e total apontam para refs do contexto; {@code totalRef} é nulo
 * quando não há total (dimensões da saúde). Os rótulos são de exibição e nunca vão ao modelo.
 */
public record Breakdown(String title, List<Row> rows, String totalRef) {

    /** Uma linha: rótulo de exibição, ref do valor e um detalhe opcional. */
    public record Row(String label, String valueRef, String detail) {

        public Row {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(valueRef, "valueRef");
        }

        /** Sem rótulo nem detalhe: podem ter nomes de comerciantes. */
        @Override
        public String toString() {
            return "Row[" + valueRef + "]";
        }
    }

    public Breakdown {
        Objects.requireNonNull(title, "title");
        rows = List.copyOf(rows);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("a breakdown needs rows");
        }
    }

    /** Sem linhas: rótulos de exibição não vão para logs. */
    @Override
    public String toString() {
        return "Breakdown[" + rows.size() + " rows]";
    }
}
