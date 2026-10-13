package com.smarthealthfinance.transactions.domain.valueobject;

import com.smarthealthfinance.shared.domain.InvalidValueException;

import java.util.regex.Pattern;

/** Código estável de uma categoria do catálogo (ex.: {@code DINING_OUT}), usado como contrato na API (ADR-0012). */
public record CategoryCode(
        String value
) {
    public static final int MAX_LENGTH = 40;

    private static final Pattern FORMAT = Pattern.compile("[A-Z][A-Z0-9_]{0," + (MAX_LENGTH - 1) + "}");

    public CategoryCode {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new InvalidValueException("categoryCode", "INVALID");
        }
    }
}
