package com.smarthealthfinance.health.domain.model;

import com.smarthealthfinance.health.domain.enums.NextStepKind;
import com.smarthealthfinance.shared.domain.Money;
import java.time.YearMonth;
import java.util.Objects;
import java.util.UUID;

/**
 * Recomendação determinística (ADR-0017 §7), sem IA e sem simulação.
 *
 * @param text         título do passo
 * @param evidence     número que justifica o passo
 * @param categoryCode categoria (só {@code REVIEW_CATEGORY}; nula = sem categoria)
 * @param month        mês ({@code REVIEW_CATEGORY} e {@code REVIEW_MONTH}; mês de vencimento em
 *                     {@code PAY_OVERDUE_INVOICE})
 * @param amount       valor ({@code BUILD_RESERVE}: quanto falta para 6 meses; {@code PAY_OVERDUE_INVOICE}: em aberto)
 * @param cardId       cartão ({@code PAY_OVERDUE_INVOICE} e {@code REVIEW_CARD_UTILIZATION})
 */
public record NextStep(NextStepKind kind, String text, String evidence, String categoryCode, YearMonth month,
                       Money amount, UUID cardId) {

    public NextStep(NextStepKind kind, String text, String evidence, String categoryCode, YearMonth month,
                    Money amount) {
        this(kind, text, evidence, categoryCode, month, amount, null);
    }

    public NextStep {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(evidence, "evidence");
    }
}
