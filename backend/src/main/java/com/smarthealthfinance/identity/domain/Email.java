package com.smarthealthfinance.identity.domain;

import com.smarthealthfinance.shared.domain.InvalidValueException;

import java.util.Locale;
import java.util.regex.Pattern;

public record Email(String value) {

    private static final int MAX_LENGTH = 254;
    private static final Pattern FORMAT = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    public Email {
        if (value == null || value.isBlank()) {
            throw new InvalidValueException("email", "REQUIRED");
        }
        value = value.strip().toLowerCase(Locale.ROOT);
        if (value.length() > MAX_LENGTH) {
            throw new InvalidValueException("email", "TOO_LONG");
        }
        if (!FORMAT.matcher(value).matches()) {
            throw new InvalidValueException("email", "INVALID_FORMAT");
        }
    }

    @Override
    public String toString() {
        int at = value.indexOf('@');
        return value.charAt(0) + "***" + value.substring(at);
    }
}
