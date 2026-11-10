package com.smarthealthfinance.copilot.domain.model;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/**
 * Preço de um modelo em USD por milhão de tokens (ADR-0039 §10). {@code longPrompt}: faixa para prompts acima de
 * {@code longPromptThreshold} tokens (ex.: Claude Haiku 5.5 acima de 100.000), aplicada à requisição inteira.
 */
public record ModelPrice(String model, Rates rates, long longPromptThreshold, Rates longPrompt) {

    public record Rates(BigDecimal input, BigDecimal output, BigDecimal cacheRead, BigDecimal cacheWrite) {
        public Rates {
            for (BigDecimal rate : new BigDecimal[] {input, output, cacheRead, cacheWrite}) {
                Objects.requireNonNull(rate, "rate");
                if (rate.signum() < 0) {
                    throw new IllegalArgumentException("rates must not be negative");
                }
            }
        }
    }

    public ModelPrice {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(rates, "rates");
        if (longPrompt != null && longPromptThreshold <= 0) {
            throw new IllegalArgumentException("longPromptThreshold must be positive when longPrompt is set");
        }
    }

    public Optional<Rates> longPromptRates() {
        return Optional.ofNullable(longPrompt);
    }
}
