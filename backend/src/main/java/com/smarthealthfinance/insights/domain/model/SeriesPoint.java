package com.smarthealthfinance.insights.domain.model;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Objects;

/** Um mês da série de evidência (ADR-0021 §7); {@code partial} marca o mês em andamento. */
public record SeriesPoint(YearMonth month, BigDecimal value, boolean partial) {

    public SeriesPoint {
        Objects.requireNonNull(month, "month");
        Objects.requireNonNull(value, "value");
    }
}
