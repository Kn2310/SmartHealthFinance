package com.smarthealthfinance.cards.domain.valueobject;

import com.smarthealthfinance.shared.domain.InvalidValueException;
import com.smarthealthfinance.shared.domain.Money;

import java.util.Currency;
import java.util.Objects;

/** Limite do cartão (ADR-0025 §1): positivo, na moeda do Workspace, com no máximo as casas da moeda. */
public record CardLimit(
        Money value
) {
    public CardLimit {
        Objects.requireNonNull(value, "value");

        if (!value.isPositive()) {
            throw new InvalidValueException("limit", "NOT_POSITIVE");
        }

        if (value.decimalPlaces() > value.currency().getDefaultFractionDigits()) {
            throw new InvalidValueException("limit", "TOO_MANY_DECIMALS");
        }
    }

    /** Interpreta o limite vindo da API, com erros sempre no campo {@code limit}. */
    public static CardLimit parse(String amount, String currency, Currency workspaceCurrency) {
        if (amount == null || amount.isBlank() || currency == null || currency.isBlank()) {
            throw new InvalidValueException("limit", "REQUIRED");
        }

        if (!currency.strip().equals(workspaceCurrency.getCurrencyCode())) {
            throw new InvalidValueException("limit", "CURRENCY");
        }

        try {
            return new CardLimit(Money.of(amount, workspaceCurrency));
        }
        catch (InvalidValueException ex) {
            if (ex.field().equals("limit")) {
                throw ex;
            }
            throw new InvalidValueException("limit", ex.reason());
        }
    }
}
