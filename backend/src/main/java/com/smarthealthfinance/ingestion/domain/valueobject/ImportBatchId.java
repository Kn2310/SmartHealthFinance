package com.smarthealthfinance.ingestion.domain.valueobject;

import com.smarthealthfinance.shared.domain.UuidV7;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ImportBatchId(UUID value) {

    public ImportBatchId {
        Objects.requireNonNull(value, "value");
    }

    public static ImportBatchId generate(Instant now) {
        return new ImportBatchId(UuidV7.generate(now));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
