package com.smarthealthfinance.insights.domain.valueobject;

import com.smarthealthfinance.analytics.domain.valueobject.AnalysisPeriod;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;

/**
 * Período avaliado P (ADR-0021 §1), um por vez: do dia 1 ao dia {@value #FIRST_PARTIAL_DAY}−1 do mês, o mês
 * anterior, fechado; a partir do dia {@value #FIRST_PARTIAL_DAY}, o mês corrente, parcial até hoje.
 */
public record EvaluationPeriod(AnalysisPeriod period, LocalDate today) {

    public static final int FIRST_PARTIAL_DAY = 7;

    public EvaluationPeriod {
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(today, "today");
    }

    /** @param today hoje no fuso de negócio (ADR-0006 §10) */
    public static EvaluationPeriod of(LocalDate today) {
        YearMonth current = YearMonth.from(today);
        YearMonth evaluated = today.getDayOfMonth() < FIRST_PARTIAL_DAY ? current.minusMonths(1) : current;
        return new EvaluationPeriod(AnalysisPeriod.resolve(evaluated.toString(), today), today);
    }

    public YearMonth month() {
        return period.month();
    }

    /** Chave do período na identidade do insight: {@code YYYY-MM}. */
    public String key() {
        return period.month().toString();
    }

    public boolean partial() {
        return period.partial();
    }
}
