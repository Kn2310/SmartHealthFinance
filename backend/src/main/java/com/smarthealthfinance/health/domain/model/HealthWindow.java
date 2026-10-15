package com.smarthealthfinance.health.domain.model;

import com.smarthealthfinance.analytics.domain.valueobject.AnalysisPeriod;
import com.smarthealthfinance.analytics.domain.valueobject.AnalyticsPeriod;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Janela W (ADR-0017 §1): até os {@value #MAX_MONTHS} últimos meses fechados até a data-base (o próprio mês, se
 * fechado; senão, até o anterior), a partir do mês do primeiro lançamento.
 *
 * @param months do mais antigo ao mais recente; vazio = n 0
 */
public record HealthWindow(List<YearMonth> months) {

    public static final int MAX_MONTHS = 6;

    public HealthWindow {
        months = List.copyOf(months);
    }

    public static HealthWindow of(AnalysisPeriod period, Optional<LocalDate> firstActivity) {
        if (firstActivity.isEmpty()) {
            return new HealthWindow(List.of());
        }
        YearMonth first = YearMonth.from(firstActivity.get());
        YearMonth last = period.partial() ? period.month().minusMonths(1) : period.month();
        List<YearMonth> months = new ArrayList<>();
        for (int i = MAX_MONTHS - 1; i >= 0; i--) {
            YearMonth month = last.minusMonths(i);
            if (!month.isBefore(first)) {
                months.add(month);
            }
        }
        return new HealthWindow(months);
    }

    public int size() {
        return months.size();
    }

    public boolean isEmpty() {
        return months.isEmpty();
    }

    /** Intervalo de datas de W; só com n ≥ 1. */
    public AnalyticsPeriod range() {
        if (months.isEmpty()) {
            throw new IllegalStateException("Janela vazia");
        }
        return AnalyticsPeriod.months(months.getFirst(), months.getLast());
    }
}
