package com.smarthealthfinance.health.domain.model;

import com.smarthealthfinance.health.domain.enums.FactorDirection;
import com.smarthealthfinance.health.domain.enums.HealthDimension;
import java.util.Objects;

/** "Sustentando o resultado" ou "Pesando no resultado" (ADR-0017 §7): o template da dimensão com os números reais. */
public record Factor(HealthDimension dimension, FactorDirection direction, String text) {

    public Factor {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(text, "text");
    }
}
