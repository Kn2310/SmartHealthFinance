package com.smarthealthfinance.cards.domain.valueobject;

import com.smarthealthfinance.shared.domain.InvalidValueException;

public record CardNickname(
        String value
) {
    public static final int MAX_LENGTH = 100;

    public CardNickname {

        if (value == null || value.isBlank()) {
            throw new InvalidValueException("nickname", "REQUIRED");
        }

        value = value.strip();

        if (value.length() > MAX_LENGTH) {
            throw new InvalidValueException("nickname", "TOO_LONG");
        }

        if (value.chars().anyMatch(Character::isISOControl)) {
            throw new InvalidValueException("nickname", "INVALID_CHARACTERS");
        }
    }
}
