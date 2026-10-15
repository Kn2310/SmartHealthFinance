package com.smarthealthfinance.health.domain.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Arredondamentos do ADR-0017 §1: razões com 4 casas, notas inteiras, ambos HALF_EVEN. */
final class Ratios {

    static final int RATIO_SCALE = 4;

    private Ratios() {
    }

    /** {@code numerator ÷ denominator} com 4 casas; o denominador nunca é zero. */
    static BigDecimal divide(BigDecimal numerator, BigDecimal denominator) {
        if (denominator.signum() == 0) {
            throw new ArithmeticException("Divisão por zero");
        }
        return numerator.divide(denominator, RATIO_SCALE, RoundingMode.HALF_EVEN);
    }

    static BigDecimal divide(long numerator, long denominator) {
        return divide(BigDecimal.valueOf(numerator), BigDecimal.valueOf(denominator));
    }

    /** Nota inteira 0–100. */
    static int toScore(BigDecimal value) {
        int score = value.setScale(0, RoundingMode.HALF_EVEN).intValueExact();
        return Math.max(0, Math.min(100, score));
    }

    /** {@code weight × value}, para combinar as partes de uma nota. */
    static BigDecimal weighted(String weight, BigDecimal value) {
        return new BigDecimal(weight).multiply(value);
    }
}
