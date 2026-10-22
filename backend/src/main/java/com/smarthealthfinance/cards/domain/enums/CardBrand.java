package com.smarthealthfinance.cards.domain.enums;

import com.smarthealthfinance.shared.domain.InvalidValueException;

/** Bandeira (ADR-0025 §1). A UI usa visual genérico, sem marca real. */
public enum CardBrand {

    VISA,
    MASTERCARD,
    ELO,
    AMEX,
    HIPERCARD,
    OTHER;

    public static CardBrand parse(String value) {
        if (value == null || value.isBlank()) {
            throw new InvalidValueException("brand", "REQUIRED");
        }

        try {
            return valueOf(value.strip());
        }
        catch (IllegalArgumentException ex) {
            throw new InvalidValueException("brand", "INVALID");
        }
    }
}
