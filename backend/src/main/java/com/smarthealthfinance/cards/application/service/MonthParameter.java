package com.smarthealthfinance.cards.application.service;

import com.smarthealthfinance.shared.domain.InvalidValueException;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/**
 * Mês {@code YYYY-MM} vindo da API. Interpretado nos casos de uso, depois da autorização (ADR-0026 §1): quem não é
 * membro recebe 404 antes de qualquer 400.
 */
public final class MonthParameter {

    private static final Pattern YEAR_MONTH = Pattern.compile("\\d{4}-\\d{2}");

    private MonthParameter() {
    }

    public static YearMonth required(String field, String value) {
        if (value == null || !YEAR_MONTH.matcher(value).matches()) {
            throw new InvalidValueException(field, "INVALID");
        }
        try {
            return YearMonth.parse(value);
        }
        catch (DateTimeParseException ex) {
            throw new InvalidValueException(field, "INVALID");
        }
    }

    public static YearMonth optional(String field, String value) {
        return value == null || value.isBlank() ? null : required(field, value);
    }
}
