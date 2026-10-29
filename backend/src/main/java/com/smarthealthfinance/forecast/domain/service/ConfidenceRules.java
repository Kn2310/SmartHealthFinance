package com.smarthealthfinance.forecast.domain.service;

import com.smarthealthfinance.forecast.domain.enums.Confidence;
import com.smarthealthfinance.forecast.domain.enums.Limitation;
import com.smarthealthfinance.forecast.domain.model.ForecastEntry;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.enums.CashFlowRole;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Confiança e limitações do ADR-0030 §5. A confiança de uma soma é a menor entre as partes estimadas.
 * <ul>
 *   <li>{@code INSUFFICIENT_HISTORY}: menos de 3 meses fechados desde o primeiro lançamento;</li>
 *   <li>{@code SHORT_HISTORY}: 3 a 5 meses; no máximo MEDIUM;</li>
 *   <li>{@code HIGH_VARIABILITY}: amplitude acima de 25% do valor central; no máximo LOW;</li>
 *   <li>{@code UNCATEGORIZED}: mais de 15% da despesa sem categoria (o limiar dos Insights); desce um nível.</li>
 * </ul>
 */
final class ConfidenceRules {

    static final int MIN_HISTORY_MONTHS = 3;
    /** A partir de 6 meses fechados o histórico deixa de ser curto. */
    static final int FULL_HISTORY_MONTHS = 6;
    /** Máximo de meses fechados na base do gasto variável (ADR-0030 §3.1). */
    static final int MAX_BASE_MONTHS = 5;
    /** Mesmo limiar de despesa sem categoria dos Insights (ADR-0021), por valor: ADR-0030 §5. */
    static final BigDecimal UNCATEGORIZED_PERCENT = BigDecimal.valueOf(15);

    private ConfidenceRules() {
    }

    /** Meses fechados (antes do mês de {@code today}) desde o mês do primeiro lançamento, inclusive. */
    static int historyMonths(Optional<LocalDate> firstActivity, LocalDate today) {
        if (firstActivity.isEmpty()) {
            return 0;
        }
        long months = ChronoUnit.MONTHS.between(YearMonth.from(firstActivity.get()), YearMonth.from(today));
        return (int) Math.max(0, months);
    }

    static boolean sufficient(int historyMonths) {
        return historyMonths >= MIN_HISTORY_MONTHS;
    }

    static List<Limitation> historyLimitations(int historyMonths) {
        if (!sufficient(historyMonths)) {
            return List.of(Limitation.INSUFFICIENT_HISTORY);
        }
        return historyMonths < FULL_HISTORY_MONTHS ? List.of(Limitation.SHORT_HISTORY) : List.of();
    }

    /** O teto que o tamanho do histórico impõe. */
    static Confidence historyCeiling(int historyMonths) {
        return historyMonths < FULL_HISTORY_MONTHS ? Confidence.MEDIUM : Confidence.HIGH;
    }

    /** Confiança da estimativa por amostras mensais (gasto variável, novas compras no cartão). */
    static Confidence sampleConfidence(int historyMonths, boolean highVariability) {
        Confidence confidence = Confidence.HIGH.cap(historyCeiling(historyMonths));
        return highVariability ? confidence.cap(Confidence.LOW) : confidence;
    }

    /** A confiança final de uma projeção: a menor das partes, no teto do histórico, e a despesa sem categoria. */
    static Confidence finalConfidence(Collection<Confidence> parts, int historyMonths, boolean uncategorized) {
        Confidence lowest = Confidence.HIGH;
        for (Confidence part : parts) {
            lowest = lowest.min(part);
        }
        lowest = lowest.cap(historyCeiling(historyMonths));
        return uncategorized ? lowest.lower() : lowest;
    }

    /** Mais de 15% da despesa dos meses sem categoria. */
    static boolean uncategorizedExceeded(List<ForecastEntry> entries, List<YearMonth> months) {
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal uncategorized = BigDecimal.ZERO;
        for (ForecastEntry entry : entries) {
            if (entry.role() != CashFlowRole.EXPENSE || !months.contains(YearMonth.from(entry.day()))) {
                continue;
            }
            total = total.add(entry.amount().amount());
            if (entry.categoryCode() == null) {
                uncategorized = uncategorized.add(entry.amount().amount());
            }
        }
        return total.signum() > 0 && uncategorized.multiply(BigDecimal.valueOf(100))
                .compareTo(total.multiply(UNCATEGORIZED_PERCENT)) > 0;
    }

    /** Limitações de uma projeção a partir das condições encontradas, em ordem estável. */
    static List<Limitation> limitations(int historyMonths, boolean highVariability, boolean uncategorized,
                                        boolean partialCardHistory) {
        List<Limitation> limitations = new ArrayList<>(historyLimitations(historyMonths));
        if (highVariability) {
            limitations.add(Limitation.HIGH_VARIABILITY);
        }
        if (uncategorized) {
            limitations.add(Limitation.UNCATEGORIZED);
        }
        if (partialCardHistory) {
            limitations.add(Limitation.PARTIAL_CARD_HISTORY);
        }
        return limitations;
    }

    static Money zero(Money reference) {
        return Money.zero(reference.currency());
    }
}
