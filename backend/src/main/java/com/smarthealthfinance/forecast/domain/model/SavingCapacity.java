package com.smarthealthfinance.forecast.domain.model;

import com.smarthealthfinance.shared.domain.Money;

import java.time.YearMonth;
import java.util.List;
import java.util.Objects;

/**
 * Capacidade de aporte mensal (ADR-0030 §7): a mediana mensal de {@code receitas recorrentes − despesas recorrentes −
 * gasto variável − parcelas comprometidas} nos meses do horizonte, sempre {@code ESTIMATED}.
 * <p>
 * {@code monthly.factPart} é o menos a mediana das parcelas comprometidas (fato); o restante é a parte estimada.
 */
public record SavingCapacity(List<MonthlyCapacity> months, Projected monthly) {

    public SavingCapacity {
        months = List.copyOf(months);
        Objects.requireNonNull(monthly, "monthly");
    }

    public record MonthlyCapacity(YearMonth month, Money central, Money low, Money high, Money committedInstallments) {}
}
