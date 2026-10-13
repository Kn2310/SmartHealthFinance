package com.smarthealthfinance.analytics.domain.model;

import java.time.YearMonth;
import java.util.Objects;

/** Fluxo de caixa de um mês civil. */
public record MonthlyFlow(YearMonth month, FlowTotals totals) {

    public MonthlyFlow {
        Objects.requireNonNull(month, "month");
        Objects.requireNonNull(totals, "totals");
    }
}
