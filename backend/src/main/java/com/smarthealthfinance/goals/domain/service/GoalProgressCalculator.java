package com.smarthealthfinance.goals.domain.service;

import com.smarthealthfinance.goals.domain.valueobject.GoalProgress;
import com.smarthealthfinance.shared.domain.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Progresso como fato (ADR-0034 §2.2): o guardado é o saldo do destino (negativo conta como zero), o percentual é
 * inteiro {@code HALF_UP} com teto 99 até atingir o alvo, e o que falta nunca é negativo. Puro e determinístico.
 */
public final class GoalProgressCalculator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private GoalProgressCalculator() {
    }

    /** @param balance o saldo do destino hoje, ou o valor congelado ao concluir */
    public static GoalProgress of(Money target, Money balance) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(balance, "balance");
        if (!target.isPositive()) {
            throw new IllegalArgumentException("o alvo precisa ser positivo");
        }

        Money zero = Money.zero(target.currency());
        Money saved = balance.amount().signum() < 0 ? zero : balance;
        boolean reached = saved.amount().compareTo(target.amount()) >= 0;
        Money remaining = reached ? zero : target.minus(saved);

        int percent;
        if (reached) {
            percent = 100;
        } else {
            int rounded = saved.amount().multiply(HUNDRED).divide(target.amount(), 0, RoundingMode.HALF_UP)
                    .intValueExact();
            percent = Math.min(rounded, 99);
        }
        return new GoalProgress(saved, percent, remaining, reached, balance);
    }
}
