package com.smarthealthfinance.health.domain.model;

import com.smarthealthfinance.health.domain.enums.NextStepKind;
import com.smarthealthfinance.shared.domain.Money;
import java.time.YearMonth;
import java.util.Objects;

/**
 * Recomendação determinística (ADR-0017 §7), sem IA e sem simulação.
 *
 * @param text         título do passo
 * @param evidence     número que justifica o passo
 * @param categoryCode categoria (só {@code REVIEW_CATEGORY}; nula = sem categoria)
 * @param month        mês (só {@code REVIEW_CATEGORY} e {@code REVIEW_MONTH})
 * @param amount       valor (só {@code BUILD_RESERVE}: quanto falta para 6 meses)
 */
public record NextStep(NextStepKind kind, String text, String evidence, String categoryCode, YearMonth month,
                       Money amount) {

    public NextStep {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(evidence, "evidence");
    }
}
