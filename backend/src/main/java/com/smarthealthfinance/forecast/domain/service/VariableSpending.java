package com.smarthealthfinance.forecast.domain.service;

import com.smarthealthfinance.forecast.domain.model.Evidence;
import com.smarthealthfinance.forecast.domain.model.ForecastEntry;
import com.smarthealthfinance.forecast.domain.model.Range;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.enums.CashFlowRole;

import java.time.YearMonth;
import java.util.List;
import java.util.function.IntPredicate;
import java.util.function.Predicate;

/**
 * Gasto variável por mês nos meses de base (ADR-0030 §3.1, §3.4, §7): a despesa que não pertence a uma série
 * elegível, medida por mês fechado; o valor central é a mediana e a faixa é o [mínimo, máximo] das amostras (§4).
 */
final class VariableSpending {

    private VariableSpending() {
    }

    /**
     * @param values   uma amostra por mês de base, na mesma ordem
     * @param evidence os meses de amostra e o valor de cada um (ADR-0030 §6)
     */
    record Estimate(List<YearMonth> months, List<Money> values, Money central, Range range,
                    boolean highVariability, List<Evidence> evidence) {}

    /**
     * @param filter   quais despesas contam
     * @param dayOfMonth quais dias do mês contam (o recorte de dias do ADR-0015 §2)
     */
    static Estimate estimate(List<ForecastEntry> entries, List<YearMonth> months, Money zero,
                             Predicate<ForecastEntry> filter, IntPredicate dayOfMonth) {
        List<Money> values = months.stream().map(month -> entries.stream()
                .filter(e -> e.role() == CashFlowRole.EXPENSE && YearMonth.from(e.day()).equals(month)
                        && dayOfMonth.test(e.day().getDayOfMonth()) && filter.test(e))
                .map(ForecastEntry::amount)
                .reduce(zero, Money::plus)).toList();
        Money central = Statistics.median(values);
        Range range = Statistics.rangeOf(values);
        List<Evidence> evidence = new java.util.ArrayList<>();
        for (int i = 0; i < months.size(); i++) {
            evidence.add(Evidence.sample(months.get(i), values.get(i)));
        }
        return new Estimate(months, values, central, range, Statistics.highVariability(central, range), evidence);
    }
}
