package com.smarthealthfinance.transactions.domain.valueobject;

import com.smarthealthfinance.shared.domain.InvalidValueException;

import java.util.regex.Pattern;

/**
 * Valor do header {@code Idempotency-Key} (spec 05.6). Opaco e comparado byte a byte:
 * não é normalizado, para que chaves distintas nunca colidam.
 */
public record IdempotencyKey(
        String value
) {
    public static final int MAX_LENGTH = 100;

    /** Caracteres não reservados de URI + ':' (UUIDs, ULIDs e prefixos como "web:..."). */
    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9._~:-]+");

    public IdempotencyKey {

        if (value == null || value.isBlank()) {
            throw new InvalidValueException("Idempotency-Key", "REQUIRED");
        }

        if (value.length() > MAX_LENGTH) {
            throw new InvalidValueException("Idempotency-Key", "TOO_LONG");
        }

        if (!ALLOWED.matcher(value).matches()) {
            throw new InvalidValueException("Idempotency-Key", "INVALID_CHARACTERS");
        }
    }
}
