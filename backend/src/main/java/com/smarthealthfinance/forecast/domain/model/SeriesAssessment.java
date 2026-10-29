package com.smarthealthfinance.forecast.domain.model;

import com.smarthealthfinance.forecast.domain.enums.SeriesEligibility;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Uma série diante de "hoje" (ADR-0030 §2).
 *
 * @param realizedThisMonth existe transação POSTED com a mesma chave e sentido em {@code expectedDay ± 3 dias} dentro
 *                          do mês corrente: a série não é projetada de novo neste mês
 * @param expectedDate      data esperada no mês corrente
 */
public record SeriesAssessment(RecurringSeries series, SeriesEligibility eligibility, boolean realizedThisMonth,
                               LocalDate expectedDate) {

    public SeriesAssessment {
        Objects.requireNonNull(series, "series");
        Objects.requireNonNull(eligibility, "eligibility");
        Objects.requireNonNull(expectedDate, "expectedDate");
    }

    public boolean isEligible() {
        return eligibility == SeriesEligibility.ELIGIBLE;
    }
}
