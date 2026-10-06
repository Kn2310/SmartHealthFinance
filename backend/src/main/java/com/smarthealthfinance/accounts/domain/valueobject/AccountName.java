package com.smarthealthfinance.accounts.domain.valueobject;

import com.smarthealthfinance.shared.domain.InvalidValueException;

public record AccountName(
        String value
) {
    public static final int MAX_LENGTH = 100;

    public AccountName {

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
