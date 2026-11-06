package com.smarthealthfinance.goals.application.service;

import com.smarthealthfinance.goals.domain.enums.GoalOriginKind;
import com.smarthealthfinance.goals.domain.enums.GoalStatus;
import com.smarthealthfinance.goals.domain.enums.GoalType;
import com.smarthealthfinance.goals.domain.valueobject.GoalPlan;
import com.smarthealthfinance.shared.domain.InvalidValueException;
import com.smarthealthfinance.shared.domain.Money;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Currency;

/** Conversão das entradas textuais da API para o domínio, com os códigos de validação estáveis (ADR-0035). */
public final class GoalInputs {

    private GoalInputs() {
    }

    public static GoalType type(String value) {
        return parse("type", value, GoalType.class);
    }

    public static GoalOriginKind origin(String value) {
        return value == null ? GoalOriginKind.MANUAL : parse("origin.kind", value, GoalOriginKind.class);
    }

    public static GoalStatus status(String value) {
        return parse("status", value, GoalStatus.class);
    }

    public static Money money(String field, String amount, String currency) {
        if (amount == null) {
            throw new InvalidValueException(field, "REQUIRED");
        }
        try {
            return Money.of(amount, currency(field, currency));
        } catch (InvalidValueException e) {
            throw new InvalidValueException(field, e.reason());
        }
    }

    /** {@code "2027-07"}; nulo = sem prazo. */
    public static YearMonth month(String field, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return YearMonth.parse(value.strip());
        } catch (DateTimeParseException e) {
            throw new InvalidValueException(field, "INVALID");
        }
    }

    private static Currency currency(String field, String code) {
        if (code == null || code.isBlank()) {
            throw new InvalidValueException(field + ".currency", "REQUIRED");
        }
        if (!GoalPlan.CURRENCY.getCurrencyCode().equals(code.strip())) {
            throw new InvalidValueException(field + ".currency", "UNSUPPORTED");
        }
        return GoalPlan.CURRENCY;
    }

    private static <E extends Enum<E>> E parse(String field, String value, Class<E> type) {
        if (value == null || value.isBlank()) {
            throw new InvalidValueException(field, "REQUIRED");
        }
        try {
            return Enum.valueOf(type, value.strip());
        } catch (IllegalArgumentException e) {
            throw new InvalidValueException(field, "INVALID");
        }
    }
}
