package com.smarthealthfinance.goals.domain.valueobject;

import com.smarthealthfinance.shared.domain.InvalidValueException;

/** Nome do objetivo: 1 a 60 caracteres, sem espaços nas pontas (ADR-0034 §1). Nunca vai para logs ou eventos. */
public record GoalName(String value) {

    public static final int MAX_LENGTH = 60;

    public GoalName {
        if (value == null || value.isBlank()) {
            throw new InvalidValueException("name", "REQUIRED");
        }
        value = value.strip();
        if (value.length() > MAX_LENGTH) {
            throw new InvalidValueException("name", "TOO_LONG");
        }
        if (value.chars().anyMatch(Character::isISOControl)) {
            throw new InvalidValueException("name", "INVALID_CHARACTERS");
        }
    }
}
