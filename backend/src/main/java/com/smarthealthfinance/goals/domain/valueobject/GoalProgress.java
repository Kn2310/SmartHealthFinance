package com.smarthealthfinance.goals.domain.valueobject;

import com.smarthealthfinance.shared.domain.Money;

import java.util.Objects;

/**
 * Progresso do objetivo, sempre fato (ADR-0034 §2.2).
 *
 * @param saved              o guardado: o saldo do destino, nunca negativo (ou o valor congelado na conclusão)
 * @param percent            0 a 100, inteiro {@code HALF_UP}, com teto 99 enquanto o alvo não foi atingido
 * @param remaining          quanto falta, nunca negativo
 * @param reached            o guardado alcançou o alvo
 * @param destinationBalance o saldo real do destino, que pode ser negativo
 */
public record GoalProgress(Money saved, int percent, Money remaining, boolean reached, Money destinationBalance) {

    public GoalProgress {
        Objects.requireNonNull(saved, "saved");
        Objects.requireNonNull(remaining, "remaining");
        Objects.requireNonNull(destinationBalance, "destinationBalance");
        if (percent < 0 || percent > 100) {
            throw new IllegalArgumentException("percent fora de 0..100");
        }
    }
}
