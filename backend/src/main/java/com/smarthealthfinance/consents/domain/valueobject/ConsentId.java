package com.smarthealthfinance.consents.domain.valueobject;

import com.smarthealthfinance.shared.domain.UuidV7;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ConsentId(UUID value) {

    public ConsentId {
        Objects.requireNonNull(value, "value");
    }

    public static ConsentId generate(Instant now) {
        return new ConsentId(UuidV7.generate(now));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
