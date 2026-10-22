package com.smarthealthfinance.cards.domain.valueobject;

import com.smarthealthfinance.shared.domain.UuidV7;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record CardInvoiceId(
        UUID value
) {
    public CardInvoiceId {
        Objects.requireNonNull(value, "value");
    }

    public static CardInvoiceId generate(Instant now) {
        return new CardInvoiceId(UuidV7.generate(now));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
