package com.smarthealthfinance.accounts.domain.valueobject;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

class AccountIdTest {

    @Test
    void rejectsNull() {
        assertThatNullPointerException().isThrownBy(() -> new AccountId(null));
    }

    @Test
    void generatesUuidV7() {
        assertThat(AccountId.generate(Instant.parse("2026-09-27T12:00:00Z")).value().version()).isEqualTo(7);
    }

    @Test
    void toStringIsTheUuid() {
        UUID uuid = UUID.randomUUID();

        assertThat(new AccountId(uuid)).hasToString(uuid.toString());
    }
}
