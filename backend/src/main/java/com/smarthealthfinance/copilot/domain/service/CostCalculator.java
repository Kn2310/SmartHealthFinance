package com.smarthealthfinance.copilot.domain.service;

import com.smarthealthfinance.copilot.domain.model.ModelPrice;
import com.smarthealthfinance.copilot.domain.valueobject.TokenUsage;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Custo determinístico de uma chamada em USD (ADR-0039 §10): soma de cada categoria de token × preço por milhão, com
 * escala 6 (a coluna {@code numeric(19,6)}) e HALF_EVEN. Prompt acima do limite paga a faixa longa inteira.
 */
public final class CostCalculator {

    public static final int SCALE = 6;
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private CostCalculator() {
    }

    public static BigDecimal cost(ModelPrice price, TokenUsage usage) {
        ModelPrice.Rates rates = price.longPromptRates()
                .filter(longRates -> usage.promptTokens() > price.longPromptThreshold())
                .orElse(price.rates());
        BigDecimal total = rates.input().multiply(BigDecimal.valueOf(usage.input()))
                .add(rates.output().multiply(BigDecimal.valueOf(usage.output())))
                .add(rates.cacheRead().multiply(BigDecimal.valueOf(usage.cacheRead())))
                .add(rates.cacheWrite().multiply(BigDecimal.valueOf(usage.cacheWrite())));
        return total.divide(MILLION, SCALE, RoundingMode.HALF_EVEN);
    }
}
