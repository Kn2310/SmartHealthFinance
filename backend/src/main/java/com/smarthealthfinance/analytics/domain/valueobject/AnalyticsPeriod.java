package com.smarthealthfinance.analytics.domain.valueobject;

import com.smarthealthfinance.shared.domain.InvalidValueException;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;

/** Intervalo de datas de negócio ({@code occurredOn}), inclusivo nas duas pontas (ADR-0006 §10). */
public record AnalyticsPeriod(LocalDate from, LocalDate to) {

    public AnalyticsPeriod {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (to.isBefore(from)) {
            throw new InvalidValueException("to", "BEFORE_FROM");
        }
    }

    public static AnalyticsPeriod of(YearMonth month) {
        return new AnalyticsPeriod(month.atDay(1), month.atEndOfMonth());
    }

    public static AnalyticsPeriod months(YearMonth first, YearMonth last) {
        return new AnalyticsPeriod(first.atDay(1), last.atEndOfMonth());
    }

    public boolean contains(LocalDate day) {
        return !day.isBefore(from) && !day.isAfter(to);
    }
}
