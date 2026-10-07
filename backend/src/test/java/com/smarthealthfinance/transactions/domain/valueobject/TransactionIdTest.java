package com.smarthealthfinance.transactions.domain.valueobject;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class TransactionIdTest {

    @Test
    void rejectsNull() {
        assertThatNullPointerException().isThrownBy(() -> new TransactionId(null));
    }

    @Test
    void generatesUuidV7() {
        assertThat(TransactionId.generate(Instant.parse("2026-09-27T12:00:00Z")).value().version()).isEqualTo(7);
    }

    @Test
    void toStringIsTheUuid() {
        UUID uuid = UUID.randomUUID();

        assertThat(new TransactionId(uuid)).hasToString(uuid.toString());
    }
}
