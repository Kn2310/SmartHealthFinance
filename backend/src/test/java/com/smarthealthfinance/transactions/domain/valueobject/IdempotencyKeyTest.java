package com.smarthealthfinance.transactions.domain.valueobject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static com.smarthealthfinance.shared.domain.InvalidValueAssert.assertInvalidValue;
import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyKeyTest {

    @Test
    void acceptsUuidsAndOpaqueTokens() {
        assertThat(new IdempotencyKey("0199a1b2-7c3d-7e4f-8a9b-0c1d2e3f4a5b").value())
                .isEqualTo("0199a1b2-7c3d-7e4f-8a9b-0c1d2e3f4a5b");
        assertThat(new IdempotencyKey("web:txn_42.retry-1").value()).isEqualTo("web:txn_42.retry-1");
    }

    /** A chave é opaca: espaços externos não são removidos, para não colidir chaves distintas. */
    @Test
    void isCaseAndWhitespaceSensitive() {
        assertThat(new IdempotencyKey("abc")).isNotEqualTo(new IdempotencyKey("ABC"));
        assertInvalidValue(() -> new IdempotencyKey(" abc "), "Idempotency-Key", "INVALID_CHARACTERS");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "   " })
    void rejectsMissingValue(String value) {
        assertInvalidValue(() -> new IdempotencyKey(value), "Idempotency-Key", "REQUIRED");
    }

    @Test
    void enforcesLengthLimits() {
        assertThat(new IdempotencyKey("a".repeat(IdempotencyKey.MAX_LENGTH)).value()).hasSize(IdempotencyKey.MAX_LENGTH);
        assertInvalidValue(() -> new IdempotencyKey("a".repeat(IdempotencyKey.MAX_LENGTH + 1)), "Idempotency-Key",
                "TOO_LONG");
    }

    @ParameterizedTest
    @ValueSource(strings = { "chave com espaço", "chave\nquebrada", "çãé", "a;b" })
    void acceptsOnlyUnreservedAsciiCharacters(String value) {
        assertInvalidValue(() -> new IdempotencyKey(value), "Idempotency-Key", "INVALID_CHARACTERS");
    }
}
