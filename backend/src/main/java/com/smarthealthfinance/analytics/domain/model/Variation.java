package com.smarthealthfinance.analytics.domain.model;

import com.smarthealthfinance.analytics.domain.enums.Trend;
import com.smarthealthfinance.shared.domain.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Variação de um valor contra uma base (ADR-0015 §3). Determinística:
 * <ul>
 *   <li>{@code difference} = atual − base (exata);</li>
 *   <li>{@code percent} = diferença ÷ base × 100, com 1 casa, HALF_EVEN; nulo quando a base é zero;</li>
 *   <li>{@code trend}: STABLE se |diferença| &lt; 5% da base (comparado sem arredondar), UP/DOWN acima disso;
 *   NEW com base zero e atual positivo; GONE com base positiva e atual zero; STABLE com as duas pontas zero.</li>
 * </ul>
 */
public record Variation(Money base, Money difference, BigDecimal percent, Trend trend) {

    /** Limiar de "Estável", em pontos percentuais. */
    public static final BigDecimal STABLE_THRESHOLD = BigDecimal.valueOf(5);

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public Variation {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(difference, "difference");
        Objects.requireNonNull(trend, "trend");
    }

    /** @return {@code null} quando não há base (comparação indisponível) */
    public static Variation of(Money current, Money base) {
        Objects.requireNonNull(current, "current");
        if (base == null) {
            return null;
        }
        Money difference = current.minus(base);
        BigDecimal baseAmount = base.amount();

        if (baseAmount.signum() == 0) {
            return new Variation(base, difference, null, current.amount().signum() == 0 ? Trend.STABLE : Trend.NEW);
        }
        BigDecimal percent = difference.amount().multiply(HUNDRED).divide(baseAmount.abs(), 1, RoundingMode.HALF_EVEN);
        if (current.amount().signum() == 0 && baseAmount.signum() > 0) {
            return new Variation(base, difference, percent, Trend.GONE);
        }
        // |diferença| × 100 < 5 × |base|: sem arredondar, para 4,96% não virar "5,0%" e sair de "Estável".
        boolean stable = difference.amount().abs().multiply(HUNDRED)
                .compareTo(STABLE_THRESHOLD.multiply(baseAmount.abs())) < 0;
        Trend trend = stable ? Trend.STABLE : difference.amount().signum() > 0 ? Trend.UP : Trend.DOWN;
        return new Variation(base, difference, percent, trend);
    }
}
