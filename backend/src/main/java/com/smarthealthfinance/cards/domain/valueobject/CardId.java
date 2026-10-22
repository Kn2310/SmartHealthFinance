package com.smarthealthfinance.cards.domain.valueobject;

import com.smarthealthfinance.shared.domain.UuidV7;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record CardId(
        UUID value
) {
    public CardId {
        Objects.requireNonNull(value, "value");
    }

    public static CardId generate(Instant now) {
        return new CardId(UuidV7.generate(now));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
