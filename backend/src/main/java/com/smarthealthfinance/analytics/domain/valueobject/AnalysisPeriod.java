package com.smarthealthfinance.analytics.domain.valueobject;

import com.smarthealthfinance.shared.domain.InvalidValueException;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Mês analisado (ADR-0015 §2): mês civil; o mês corrente é parcial e vai até "hoje" no fuso de negócio.
 * <p>
 * As comparações usam o <b>mesmo recorte de dias</b> em outros meses ({@link #window}): com o mês parcial até o dia
 * 22, cada mês de comparação também vai de 1 a 22 (limitado ao tamanho dele), para nunca comparar parcial com cheio.
 *
 * @param partial o mês ainda não terminou
 */
public record AnalysisPeriod(YearMonth month, LocalDate from, LocalDate to, boolean partial) {

    public AnalysisPeriod {
        Objects.requireNonNull(month, "month");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
    }

    /**
     * @param requested {@code YYYY-MM}; nulo = mês corrente
     * @param today     data corrente no fuso de negócio
     * @throws InvalidValueException {@code month} {@code INVALID} (formato) ou {@code FUTURE}
     */
    public static AnalysisPeriod resolve(String requested, LocalDate today) {
        Objects.requireNonNull(today, "today");
        YearMonth current = YearMonth.from(today);
        YearMonth month = requested == null || requested.isBlank() ? current : parse(requested.strip());
        if (month.isAfter(current)) {
            throw new InvalidValueException("month", "FUTURE");
        }
        if (month.equals(current)) {
            return new AnalysisPeriod(month, month.atDay(1), today, !today.equals(month.atEndOfMonth()));
        }
        return new AnalysisPeriod(month, month.atDay(1), month.atEndOfMonth(), false);
    }

    private static YearMonth parse(String value) {
        if (!value.matches("\\d{4}-\\d{2}")) {
            throw new InvalidValueException("month", "INVALID");
        }
        try {
            return YearMonth.parse(value);
        }
        catch (DateTimeParseException invalid) {
            throw new InvalidValueException("month", "INVALID");
        }
    }

    public AnalyticsPeriod range() {
        return new AnalyticsPeriod(from, to);
    }

    public int daysElapsed() {
        return to.getDayOfMonth();
    }

    public int daysInMonth() {
        return month.lengthOfMonth();
    }

    /** O mesmo recorte de dias em {@code other}: mês cheio se este não é parcial; senão do dia 1 ao dia de hoje. */
    public AnalyticsPeriod window(YearMonth other) {
        if (!partial) {
            return AnalyticsPeriod.of(other);
        }
        return new AnalyticsPeriod(other.atDay(1), other.atDay(Math.min(daysElapsed(), other.lengthOfMonth())));
    }

    /** Os {@code count} meses que terminam neste (inclusive), do mais antigo ao mais recente. */
    public List<YearMonth> lastMonths(int count) {
        List<YearMonth> months = new ArrayList<>(count);
        for (int i = count - 1; i >= 0; i--) {
            months.add(month.minusMonths(i));
        }
        return months;
    }

    /** Período de um mês do histórico: o próprio período neste mês; mês cheio nos anteriores. */
    public AnalyticsPeriod historyRange(YearMonth other) {
        return other.equals(month) ? range() : AnalyticsPeriod.of(other);
    }
}
