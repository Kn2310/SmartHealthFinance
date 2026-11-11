package com.smarthealthfinance.copilot.domain.model;

import java.time.Duration;
import java.util.Objects;

/**
 * Limites do pipeline (ADR-0039 §2, §5): orçamento total de tempo, tempo mínimo para valer a pena chamar o modelo de
 * novo, teto do contexto em tokens estimados e tamanho máximo da pergunta.
 */
public record PipelineSettings(Duration budget, Duration minimumCall, int contextTokenCeiling, int maxQuestionChars) {

    public static final PipelineSettings DEFAULT = new PipelineSettings(Duration.ofSeconds(30), Duration.ofSeconds(3),
            12_000, 500);

    public PipelineSettings {
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(minimumCall, "minimumCall");
        if (budget.isNegative() || budget.isZero() || minimumCall.isNegative() || contextTokenCeiling < 1
                || maxQuestionChars < 1) {
            throw new IllegalArgumentException("invalid pipeline settings");
        }
    }
}
