package com.smarthealthfinance.cards.domain.service;

import com.smarthealthfinance.shared.domain.InvalidValueException;
import com.smarthealthfinance.shared.domain.Money;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Divide uma compra em parcelas (ADR-0025 §6): base = total ÷ n truncado nos centavos; o resto vai para a 1ª
 * parcela. A soma das parcelas é sempre exatamente o total. Ex.: 100,00 em 3× → 33,34 + 33,33 + 33,33.
 */
public final class InstallmentSplitter {

    public static final int MAX_INSTALLMENTS = 48;

    private InstallmentSplitter() {
    }

    public static List<Money> split(Money total, int count) {
        Objects.requireNonNull(total, "total");

        if (count < 1 || count > MAX_INSTALLMENTS) {
            throw new InvalidValueException("installmentCount", "OUT_OF_RANGE");
        }

        int fractionDigits = total.currency().getDefaultFractionDigits();
        if (!total.isPositive() || total.decimalPlaces() > fractionDigits) {
            throw new IllegalArgumentException("O total da compra precisa ser positivo e na escala da moeda");
        }

        BigDecimal cents = total.amount().movePointRight(fractionDigits).setScale(0);
        BigDecimal installments = BigDecimal.valueOf(count);
        if (cents.compareTo(installments) < 0) {
            throw new InvalidValueException("installmentCount", "TOO_MANY_FOR_AMOUNT");
        }

        BigDecimal[] division = cents.divideAndRemainder(installments);
        Money base = new Money(division[0].movePointLeft(fractionDigits), total.currency());
        Money first = new Money(division[0].add(division[1]).movePointLeft(fractionDigits), total.currency());

        List<Money> parts = new ArrayList<>(count);
        parts.add(first);
        for (int i = 1; i < count; i++) {
            parts.add(base);
        }
        return List.copyOf(parts);
    }
}
