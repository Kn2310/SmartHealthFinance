package com.smarthealthfinance.copilot.application.usecase;

import com.smarthealthfinance.copilot.application.dto.ConversationCursor;
import com.smarthealthfinance.shared.domain.InvalidValueException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Cursor opaco da listagem (ADR-0040 §2): vai e volta, e lixo vira VALIDATION_FAILED. */
class ConversationCursorTest {

    @Test
    void roundTripsTheTimeAndTheId() {
        ConversationCursor cursor = new ConversationCursor(Instant.parse("2026-11-11T12:00:00.123456Z"), UUID.randomUUID());

        String encoded = cursor.encode();

        assertThat(encoded).matches("[A-Za-z0-9_-]+");
        assertThat(ConversationCursor.parse(encoded)).isEqualTo(cursor);
    }

    @Test
    void anythingElseIsInvalid() {
        for (String bad : new String[] {"nao-e-um-cursor", "", "%%%", Base64.getUrlEncoder().encodeToString("x|y".getBytes()),
                Base64.getUrlEncoder().encodeToString("sem-barra".getBytes())}) {
            assertThatThrownBy(() -> ConversationCursor.parse(bad)).isInstanceOf(InvalidValueException.class);
        }
    }
}
