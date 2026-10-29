package com.smarthealthfinance.forecast.domain.model;

import com.smarthealthfinance.shared.domain.Money;

import java.util.Objects;

/** Faixa [mínimo, máximo] das amostras usadas (ADR-0030 §4). */
public record Range(Money min, Money max) {

    public Range {
        Objects.requireNonNull(min, "min");
        Objects.requireNonNull(max, "max");
        if (min.amount().compareTo(max.amount()) > 0) {
            throw new IllegalArgumentException("min maior que max");
        }
    }

    /** Faixa de um valor exato (fato ou valor editado pelo usuário). */
    public static Range point(Money value) {
        return new Range(value, value);
    }

    /** Somar estimativas soma os mínimos e os máximos (ADR-0030 §4). */
    public Range plus(Range other) {
        return new Range(min.plus(other.min), max.plus(other.max));
    }

    public boolean contains(Money value) {
        return value.amount().compareTo(min.amount()) >= 0 && value.amount().compareTo(max.amount()) <= 0;
    }

    public Money amplitude() {
        return max.minus(min);
    }
}
