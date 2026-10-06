package com.smarthealthfinance.accounts.domain.valueobject;

import com.smarthealthfinance.shared.domain.UuidV7;
import org.jspecify.annotations.NonNull;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AccountId(
        UUID value
) {
    public AccountId {
        Objects.requireNonNull(value, "value");
    }

    public static AccountId generate(Instant now) {
        return new AccountId(UuidV7.generate(now));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
