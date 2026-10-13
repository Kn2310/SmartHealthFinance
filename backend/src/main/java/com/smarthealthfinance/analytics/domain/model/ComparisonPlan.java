package com.smarthealthfinance.analytics.domain.model;

import com.smarthealthfinance.analytics.domain.enums.ComparisonBase;
import com.smarthealthfinance.analytics.domain.enums.ComparisonState;
import com.smarthealthfinance.analytics.domain.valueobject.AnalysisPeriod;
import com.smarthealthfinance.analytics.domain.valueobject.AnalyticsPeriod;
import com.smarthealthfinance.shared.domain.InvalidValueException;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * As duas bases de comparação de um período (ADR-0015 §2/§4), sempre no mesmo recorte de dias:
 * <ul>
 *   <li>{@code previous}: o mês anterior; nulo se for anterior ao primeiro lançamento do Workspace;</li>
 *   <li>{@code averageWindows}: os N meses anteriores, só os a partir do mês do primeiro lançamento. Dentro deles, mês
 *   sem movimento conta como zero (média mensal real).</li>
 * </ul>
 *
 * @param requestedMonths N pedido (1–12)
 */
public record ComparisonPlan(ComparisonBase selected, AnalyticsPeriod previous, List<YearMonth> averageMonths,
                             List<AnalyticsPeriod> averageWindows, int requestedMonths) {

    public static final int DEFAULT_AVERAGE_MONTHS = 5;
    public static final int MAX_AVERAGE_MONTHS = 12;
    /** A partir de quantos meses a média é considerada sólida. */
    public static final int SOLID_AVERAGE_MONTHS = 3;

    public ComparisonPlan {
        Objects.requireNonNull(selected, "selected");
        averageMonths = List.copyOf(averageMonths);
        averageWindows = List.copyOf(averageWindows);
    }

    /**
     * @param firstActivity primeiro dia com movimento no Workspace; vazio = nenhum
     * @throws InvalidValueException {@code averageMonths} {@code OUT_OF_RANGE}
     */
    public static ComparisonPlan of(AnalysisPeriod period, ComparisonBase selected, Integer averageMonths,
                                    Optional<LocalDate> firstActivity) {
        int requested = averageMonths == null ? DEFAULT_AVERAGE_MONTHS : averageMonths;
        if (requested < 1 || requested > MAX_AVERAGE_MONTHS) {
            throw new InvalidValueException("averageMonths", "OUT_OF_RANGE");
        }
        if (firstActivity.isEmpty()) {
            return new ComparisonPlan(selected, null, List.of(), List.of(), requested);
        }
        YearMonth first = YearMonth.from(firstActivity.get());

        YearMonth previousMonth = period.month().minusMonths(1);
        AnalyticsPeriod previous = previousMonth.isBefore(first) ? null : period.window(previousMonth);

        List<YearMonth> months = new ArrayList<>();
        List<AnalyticsPeriod> windows = new ArrayList<>();
        for (int i = requested; i >= 1; i--) {
            YearMonth month = period.month().minusMonths(i);
            if (!month.isBefore(first)) {
                months.add(month);
                windows.add(period.window(month));
            }
        }
        return new ComparisonPlan(selected, previous, months, windows, requested);
    }

    public static ComparisonBase parseBase(String value) {
        if (value == null || value.isBlank()) {
            return ComparisonBase.PREVIOUS;
        }
        try {
            return ComparisonBase.valueOf(value.strip());
        }
        catch (IllegalArgumentException invalid) {
            throw new InvalidValueException("compareWith", "INVALID");
        }
    }

    public int monthsUsed() {
        return averageMonths.size();
    }

    public ComparisonState averageState() {
        if (averageMonths.isEmpty()) {
            return ComparisonState.INSUFFICIENT_DATA;
        }
        return averageMonths.size() < SOLID_AVERAGE_MONTHS ? ComparisonState.LIMITED : ComparisonState.AVAILABLE;
    }

    public boolean hasPrevious() {
        return previous != null;
    }

    public boolean hasAverage() {
        return !averageWindows.isEmpty();
    }

    /** A base escolhida tem dados? */
    public boolean selectedAvailable() {
        return selected == ComparisonBase.PREVIOUS ? hasPrevious() : hasAverage();
    }

    /** Primeiro dia que algum cálculo do plano precisa ler. */
    public LocalDate earliestDay(LocalDate fallback) {
        LocalDate earliest = fallback;
        if (previous != null && previous.from().isBefore(earliest)) {
            earliest = previous.from();
        }
        for (AnalyticsPeriod window : averageWindows) {
            if (window.from().isBefore(earliest)) {
                earliest = window.from();
            }
        }
        return earliest;
    }
}
