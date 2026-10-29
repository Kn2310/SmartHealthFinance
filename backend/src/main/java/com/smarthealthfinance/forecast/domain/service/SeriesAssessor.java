package com.smarthealthfinance.forecast.domain.service;

import com.smarthealthfinance.forecast.domain.enums.Confidence;
import com.smarthealthfinance.forecast.domain.enums.SeriesEligibility;
import com.smarthealthfinance.forecast.domain.model.ForecastEntry;
import com.smarthealthfinance.forecast.domain.model.RecurringSeries;
import com.smarthealthfinance.forecast.domain.model.SeriesAssessment;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

/**
 * Situação das séries diante de "hoje" (ADR-0030 §2): elegibilidade para as projeções, {@code STALE} e ocorrência no
 * ciclo.
 * <ul>
 *   <li>{@code CONFIRMED}: sempre elegível, exceto {@code STALE} (3 meses sem ocorrência);</li>
 *   <li>{@code DETECTED}: elegível se ainda é candidata e a confiança é MEDIUM ou HIGH (rotulada "Detectada");</li>
 *   <li>{@code IGNORED} e confiança LOW nunca entram.</li>
 * </ul>
 * Ocorrência no ciclo: existe transação com a mesma chave e o mesmo sentido em {@code expectedDay ± 3 dias} dentro do
 * mês corrente, até hoje.
 */
public final class SeriesAssessor {

    /** Meses fechados sem ocorrência que tornam uma série confirmada {@code STALE}. */
    public static final int STALE_MONTHS = 3;

    private SeriesAssessor() {
    }

    /**
     * @param candidates as séries que a detecção de hoje encontrou ({@link RecurrenceDetector#detect})
     */
    public static List<SeriesAssessment> assess(List<RecurringSeries> series, List<RecurringSeries> candidates,
                                                List<ForecastEntry> entries, LocalDate today) {
        Set<RecurrenceDetector.SeriesRef> candidateRefs = candidates.stream().map(RecurrenceMerge::ref)
                .collect(java.util.stream.Collectors.toSet());
        YearMonth month = YearMonth.from(today);
        return series.stream()
                .map(s -> new SeriesAssessment(s, eligibility(s, candidateRefs, entries, today),
                        isRealized(s, entries, month, today), s.expectedDateIn(month)))
                .toList();
    }

    static SeriesEligibility eligibility(RecurringSeries series, Set<RecurrenceDetector.SeriesRef> candidates,
                                         List<ForecastEntry> entries, LocalDate today) {
        return switch (series.status()) {
            case IGNORED -> SeriesEligibility.NOT_ELIGIBLE;
            case CONFIRMED -> isStale(series, entries, today) ? SeriesEligibility.STALE : SeriesEligibility.ELIGIBLE;
            case DETECTED -> candidates.contains(RecurrenceMerge.ref(series)) && series.confidence() != Confidence.LOW
                    ? SeriesEligibility.ELIGIBLE : SeriesEligibility.NOT_ELIGIBLE;
        };
    }

    /** Nenhuma transação da série nos {@value #STALE_MONTHS} meses fechados anteriores nem neste mês até hoje. */
    static boolean isStale(RecurringSeries series, List<ForecastEntry> entries, LocalDate today) {
        LocalDate from = YearMonth.from(today).minusMonths(STALE_MONTHS).atDay(1);
        return entries.stream().noneMatch(e -> SeriesKeys.matches(e, series) && !e.day().isBefore(from)
                && !e.day().isAfter(today));
    }

    /** Ocorrência no ciclo: mesma chave e sentido em {@code expectedDay ± 3 dias} dentro do mês, até hoje. */
    static boolean isRealized(RecurringSeries series, List<ForecastEntry> entries, YearMonth month, LocalDate today) {
        LocalDate expected = series.expectedDateIn(month);
        return entries.stream().anyMatch(e -> SeriesKeys.matches(e, series) && YearMonth.from(e.day()).equals(month)
                && !e.day().isAfter(today)
                && Math.abs(ChronoUnit.DAYS.between(expected, e.day())) <= RecurringSeries.DATE_TOLERANCE_DAYS);
    }

}
