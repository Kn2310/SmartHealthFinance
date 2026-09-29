package com.smarthealthfinance.identity.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class WorkspaceIdTest {
    @Test
    void rejectsNull() {
        assertThatNullPointerException().isThrownBy(() -> new WorkspaceId(null));
    }

    @Test
    void generatesUuidV7() {
        assertThat(WorkspaceId.generate(Instant.parse("2026-09-27T12:00:00Z")).value().version()).isEqualTo(7);
    }

    @Test
    void toStringIsTheUuid() {
        UUID uuid = UUID.randomUUID();

        assertThat(new WorkspaceId(uuid)).hasToString(uuid.toString());
    }
}
