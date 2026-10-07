package com.smarthealthfinance.transactions.domain.valueobject;

import com.smarthealthfinance.shared.domain.InvalidValueException;

/** Texto exibido na lista (ex.: comerciante). Dado financeiro: nunca vai para logs. */
public record TransactionDescription(
        String value
) {
    public static final int MAX_LENGTH = 200;

    public TransactionDescription {

        if (value == null || value.isBlank()) {
            throw new InvalidValueException("description", "REQUIRED");
        }

        value = value.strip();

        if (value.length() > MAX_LENGTH) {
            throw new InvalidValueException("description", "TOO_LONG");
        }

        if (value.chars().anyMatch(Character::isISOControl)) {
            throw new InvalidValueException("description", "INVALID_CHARACTERS");
        }
    }
}
