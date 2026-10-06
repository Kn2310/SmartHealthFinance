package com.smarthealthfinance.accounts.domain.valueobject;

import com.smarthealthfinance.shared.domain.InvalidValueException;

import java.util.Optional;

public record InstitutionName(
        String value
) {
    public static final int MAX_LENGTH = 100;

    public InstitutionName {

        if (value == null || value.isBlank()) {
            throw new InvalidValueException("institutionName", "REQUIRED");
        }

        value = value.strip();

        if (value.length() > MAX_LENGTH) {
            throw new InvalidValueException("institutionName", "TOO_LONG");
        }

        if (value.chars().anyMatch(Character::isISOControl)) {
            throw new InvalidValueException("institutionName", "INVALID_CHARACTERS");
        }
    }

    public static Optional<InstitutionName> ofNullable(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(new InstitutionName(value));
    }
}
