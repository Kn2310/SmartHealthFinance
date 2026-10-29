package com.smarthealthfinance.forecast.domain.service;

import com.smarthealthfinance.forecast.domain.model.Range;
import com.smarthealthfinance.shared.domain.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;

/**
 * Estatísticas exatas do ADR-0030 §4: mediana e [mínimo, máximo]. Sem aleatoriedade e sem desvio. Divisões só aqui,
 * com 2 casas e HALF_EVEN (a mesma regra da média do ADR-0015).
 */
final class Statistics {

    /** Casas das divisões (centavos). */
    static final int SCALE = 2;
    /** Amplitude acima de 25% do valor central é {@code HIGH_VARIABILITY} (ADR-0030 §5). */
    private static final BigDecimal VARIABILITY_DIVISOR = BigDecimal.valueOf(4);

    private Statistics() {
    }

    /** Mediana; com quantidade par, a média dos dois do meio (2 casas, HALF_EVEN). A lista não pode ser vazia. */
    static Money median(List<Money> values) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("Sem amostras");
        }
        List<Money> sorted = values.stream().sorted(Comparator.comparing(Money::amount)).toList();
        int size = sorted.size();
        if (size % 2 == 1) {
            return sorted.get(size / 2);
        }
        Money sum = sorted.get(size / 2 - 1).plus(sorted.get(size / 2));
        return new Money(sum.amount().divide(BigDecimal.valueOf(2), SCALE, RoundingMode.HALF_EVEN), sum.currency());
    }

    static Range rangeOf(List<Money> values) {
        Money min = values.stream().min(Comparator.comparing(Money::amount)).orElseThrow();
        Money max = values.stream().max(Comparator.comparing(Money::amount)).orElseThrow();
        return new Range(min, max);
    }

    static Money atLeastZero(Money value) {
        return value.amount().signum() < 0 ? Money.zero(value.currency()) : value;
    }

    static Range atLeastZero(Range range) {
        return new Range(atLeastZero(range.min()), atLeastZero(range.max()));
    }

    static Money max(Money a, Money b) {
        return a.amount().compareTo(b.amount()) >= 0 ? a : b;
    }

    /** {@code value × factor}, 2 casas HALF_EVEN. */
    static Money times(Money value, BigDecimal factor) {
        return new Money(value.amount().multiply(factor).setScale(SCALE, RoundingMode.HALF_EVEN), value.currency());
    }

    /**
     * Amplitude (máx − mín) acima de 25% do valor central. Com valor central zero, qualquer amplitude positiva conta.
     */
    static boolean highVariability(Money central, Range range) {
        BigDecimal amplitude = range.amplitude().amount();
        if (central.amount().signum() == 0) {
            return amplitude.signum() > 0;
        }
        return amplitude.compareTo(central.amount().abs().divide(VARIABILITY_DIVISOR)) > 0;
    }
}
