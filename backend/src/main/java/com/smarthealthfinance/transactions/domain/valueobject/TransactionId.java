package com.smarthealthfinance.transactions.domain.valueobject;

import com.smarthealthfinance.shared.domain.UuidV7;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record TransactionId(
        UUID value
) {
    public TransactionId {
        Objects.requireNonNull(value, "value");
    }

    public static TransactionId generate(Instant now) {
        return new TransactionId(UuidV7.generate(now));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
