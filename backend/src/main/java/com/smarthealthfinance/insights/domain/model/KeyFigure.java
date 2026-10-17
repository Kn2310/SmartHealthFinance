package com.smarthealthfinance.insights.domain.model;

import com.smarthealthfinance.insights.domain.enums.FigureKind;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Número-chave do resumo (ADR-0021 §7), sempre com o período.
 *
 * @param code  identificador estável (ex.: {@code current}, {@code average}, {@code difference})
 * @param label rótulo em português
 */
public record KeyFigure(String code, String label, FigureKind kind, BigDecimal value, LocalDate from, LocalDate to) {

    public KeyFigure {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
    }
}
