package com.smarthealthfinance.transactions.domain.enums;

import com.smarthealthfinance.shared.domain.InvalidValueException;

import java.util.Optional;

/** Direção de um ADJUSTMENT: o valor é sempre positivo, então o sinal precisa ser explícito. */
public enum AdjustmentDirection {

    INCREASE,
    DECREASE;

    /** Ausente (nulo/em branco) é válido aqui; quem exige a direção é o tipo da transação. */
    public static Optional<AdjustmentDirection> parseOptional(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }

        try {
            return Optional.of(valueOf(value.strip()));
        }
        catch (IllegalArgumentException ex) {
            throw new InvalidValueException("adjustmentDirection", "INVALID");
        }
    }
}
