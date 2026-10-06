package com.smarthealthfinance.accounts.domain.enums;

import com.smarthealthfinance.shared.domain.InvalidValueException;

public enum AccountType {

    CHECKING,
    SAVINGS,
    PAYMENT,
    OTHER;

    public static AccountType parse(String value) {
        if (value == null || value.isBlank()) {
            throw new InvalidValueException("type", "REQUIRED");
        }

        try {
            return valueOf(value.strip());
        }
        catch (IllegalArgumentException ex) {
            throw new InvalidValueException("type", "INVALID");
        }
    }
}
