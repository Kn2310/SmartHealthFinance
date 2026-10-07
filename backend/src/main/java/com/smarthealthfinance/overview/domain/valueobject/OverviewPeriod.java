package com.smarthealthfinance.overview.domain.valueobject;

import com.smarthealthfinance.overview.domain.enums.PeriodType;
import com.smarthealthfinance.shared.domain.InvalidValueException;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Período analisado: datas de negócio ({@code occurredOn}), inclusivas nas duas pontas (ADR-0006).
 * O saldo é calculado até {@link #to()}; o mês corrente é parcial (vai até "hoje").
 */
public record OverviewPeriod(PeriodType type, LocalDate from, LocalDate to) {

    /** Limite para impedir varreduras ilimitadas do histórico. */
    public static final int MAX_DAYS = 366;

    public OverviewPeriod {
        Objects.requireNonNull(type, "type");
        if (from == null) {
            throw new InvalidValueException("from", "REQUIRED");
        }
        if (to == null) {
            throw new InvalidValueException("to", "REQUIRED");
        }
        if (to.isBefore(from)) {
            throw new InvalidValueException("to", "BEFORE_FROM");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw new InvalidValueException("to", "RANGE_TOO_LARGE");
        }
    }

    /**
     * @param today data corrente no fuso de negócio
     * @param from  e {@code to}: só para CUSTOM (obrigatórios); proibidos nos demais tipos
     */
    public static OverviewPeriod resolve(PeriodType type, LocalDate from, LocalDate to, LocalDate today) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(today, "today");

        if (type == PeriodType.CUSTOM) {
            return new OverviewPeriod(type, from, to);
        }
        if (from != null) {
            throw new InvalidValueException("from", "NOT_ALLOWED");
        }
        if (to != null) {
            throw new InvalidValueException("to", "NOT_ALLOWED");
        }

        LocalDate firstOfMonth = today.withDayOfMonth(1);
        return type == PeriodType.CURRENT_MONTH
                ? new OverviewPeriod(type, firstOfMonth, today)
                : new OverviewPeriod(type, firstOfMonth.minusMonths(1), firstOfMonth.minusDays(1));
    }

    /** Data em que o saldo é medido. */
    public LocalDate balanceAsOf() {
        return to;
    }
}
