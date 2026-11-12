package com.smarthealthfinance.copilot.domain.model;

import java.time.YearMonth;
import java.util.Objects;
import java.util.UUID;

/**
 * Contexto de entrada de uma pergunta (ADR-0040 §3), vindo dos pontos "Perguntar ao Copilot": o item que a pessoa
 * estava vendo. O alvo é validado no Workspace e vira o alvo da intenção, no lugar da escolha do modelo.
 */
public sealed interface EntryContext {

    /** Insight do Workspace. */
    record Insight(UUID insightId) implements EntryContext {
        public Insight {
            Objects.requireNonNull(insightId, "insightId");
        }
    }

    /** Fatura do cartão, pelo mês de vencimento. */
    record Invoice(UUID cardId, YearMonth month) implements EntryContext {
        public Invoice {
            Objects.requireNonNull(cardId, "cardId");
            Objects.requireNonNull(month, "month");
        }
    }

    /** Parcelamento (a compra parcelada) de um cartão. */
    record InstallmentPlan(UUID cardId, UUID planId) implements EntryContext {
        public InstallmentPlan {
            Objects.requireNonNull(cardId, "cardId");
            Objects.requireNonNull(planId, "planId");
        }
    }

    /** Objetivo do Workspace. */
    record Goal(UUID goalId) implements EntryContext {
        public Goal {
            Objects.requireNonNull(goalId, "goalId");
        }
    }

    /** O tipo, como vai na API ({@code INSIGHT}, {@code INVOICE}, {@code INSTALLMENT_PLAN}, {@code GOAL}). */
    default String kind() {
        return switch (this) {
            case Insight ignored -> "INSIGHT";
            case Invoice ignored -> "INVOICE";
            case InstallmentPlan ignored -> "INSTALLMENT_PLAN";
            case Goal ignored -> "GOAL";
        };
    }
}
