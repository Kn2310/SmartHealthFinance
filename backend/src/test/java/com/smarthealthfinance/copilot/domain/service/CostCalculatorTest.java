package com.smarthealthfinance.copilot.domain.service;

import com.smarthealthfinance.copilot.domain.model.ModelPrice;
import com.smarthealthfinance.copilot.domain.valueobject.TokenUsage;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Preços da tabela pricing-2026-11 (ADR-0039 §10): custo exato em USD, escala 6. */
class CostCalculatorTest {

    private static final ModelPrice HAIKU = new ModelPrice("claude-haiku-5-5",
            rates("0.10", "0.50", "0.01", "0.125"), 100_000, rates("0.50", "2.50", "0.05", "0.625"));
    private static final ModelPrice SONNET = new ModelPrice("claude-sonnet-5-5",
            rates("2.00", "10.00", "0.10", "2.50"), 0, null);

    private static ModelPrice.Rates rates(String input, String output, String cacheRead, String cacheWrite) {
        return new ModelPrice.Rates(new BigDecimal(input), new BigDecimal(output), new BigDecimal(cacheRead),
                new BigDecimal(cacheWrite));
    }

    @Test
    void intentCallOnHaiku() {
        // 2.000 × 0,10 + 100 × 0,50 = 250 → US$ 0,000250
        assertThat(CostCalculator.cost(HAIKU, new TokenUsage(2_000, 100, 0, 0))).isEqualByComparingTo("0.000250");
    }

    @Test
    void answerCallOnSonnetWithCache() {
        // 6.000 × 2 + 600 × 10 + 2.000 × 0,10 + 0 = 12.000 + 6.000 + 200 = 18.200 → US$ 0,018200
        BigDecimal cost = CostCalculator.cost(SONNET, new TokenUsage(6_000, 600, 2_000, 0));
        assertThat(cost).isEqualByComparingTo("0.018200");
        assertThat(cost.scale()).isEqualTo(CostCalculator.SCALE);
    }

    @Test
    void cacheWriteIsPricedSeparately() {
        // 1.000 × 2,50 = 2.500 → US$ 0,002500
        assertThat(CostCalculator.cost(SONNET, new TokenUsage(0, 0, 0, 1_000))).isEqualByComparingTo("0.002500");
    }

    @Test
    void haikuPromptOverTheThresholdPaysTheLongRateForTheWholeRequest() {
        // prompt = 90.000 + 20.000 de cache = 110.000 > 100.000: 90.000 × 0,50 + 10 × 2,50 + 20.000 × 0,05
        BigDecimal cost = CostCalculator.cost(HAIKU, new TokenUsage(90_000, 10, 20_000, 0));
        assertThat(cost).isEqualByComparingTo("0.046025");
    }

    @Test
    void exactlyAtTheThresholdStillPaysTheBaseRate() {
        assertThat(CostCalculator.cost(HAIKU, new TokenUsage(100_000, 0, 0, 0))).isEqualByComparingTo("0.010000");
    }

    @Test
    void roundsHalfEvenAtSixDecimals() {
        // 1 token de entrada no Haiku = 0,10 / 1.000.000 = 0,0000001 → 0,000000
        assertThat(CostCalculator.cost(HAIKU, new TokenUsage(1, 0, 0, 0))).isEqualByComparingTo("0.000000");
        // 5 tokens = 0,0000005 → HALF_EVEN para 0,000000; 15 tokens = 0,0000015 → 0,000002
        assertThat(CostCalculator.cost(HAIKU, new TokenUsage(5, 0, 0, 0))).isEqualByComparingTo("0.000000");
        assertThat(CostCalculator.cost(HAIKU, new TokenUsage(15, 0, 0, 0))).isEqualByComparingTo("0.000002");
    }

    @Test
    void rejectsNegativeTokensAndRates() {
        assertThatThrownBy(() -> new TokenUsage(-1, 0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rates("-0.1", "0", "0", "0")).isInstanceOf(IllegalArgumentException.class);
    }
}
