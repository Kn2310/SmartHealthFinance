package com.smarthealthfinance.cards.domain.valueobject;

import com.smarthealthfinance.shared.domain.InvalidValueException;

import java.util.regex.Pattern;

/**
 * Os 4 últimos dígitos do cartão: o único dado do número que o sistema guarda (ADR-0025 §1). Nunca o número completo,
 * CVV, validade ou senha. Sem {@code strip}: qualquer coisa além de exatamente 4 dígitos é recusada.
 */
public record CardLast4(
        String value
) {
    private static final Pattern FOUR_DIGITS = Pattern.compile("[0-9]{4}");

    public CardLast4 {

        if (value == null || value.isEmpty()) {
            throw new InvalidValueException("last4", "REQUIRED");
        }

        if (!FOUR_DIGITS.matcher(value).matches()) {
            throw new InvalidValueException("last4", "INVALID");
        }
    }

    /** Nunca expõe os dígitos em logs ou mensagens. */
    @Override
    public String toString() {
        return "CardLast4[****]";
    }
}
