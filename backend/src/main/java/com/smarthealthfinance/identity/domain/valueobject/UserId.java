package com.smarthealthfinance.identity.domain.valueobject;

import com.smarthealthfinance.shared.domain.UuidV7;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record UserId(UUID value) {

    public UserId {
        Objects.requireNonNull(value, "value");
    }

    public static UserId generate(Instant now) {
        return new UserId(UuidV7.generate(now));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
