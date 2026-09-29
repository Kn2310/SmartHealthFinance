package com.smarthealthfinance.identity.domain.valueobject;

import com.smarthealthfinance.shared.domain.InvalidValueException;

public record DisplayName(String value) {

    public static final int MAX_LENGTH = 100;

    public DisplayName {
        if (value == null || value.isBlank()) {
            throw new InvalidValueException("displayName", "REQUIRED");
        }
        value = value.strip();
        if (value.length() > MAX_LENGTH) {
            throw new InvalidValueException("displayName", "TOO_LONG");
        }
        if (value.chars().anyMatch(Character::isISOControl)) {
            throw new InvalidValueException("displayName", "INVALID_CHARACTERS");
        }
    }
}
