package com.smarthealthfinance.transactions.domain.valueobject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static com.smarthealthfinance.shared.domain.InvalidValueAssert.assertInvalidValue;
import static org.assertj.core.api.Assertions.assertThat;

class TransactionDescriptionTest {

    @Test
    void stripsSurroundingWhitespace() {
        assertThat(new TransactionDescription("  Bistrô Lume ").value()).isEqualTo("Bistrô Lume");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "   " })
    void rejectsMissingValue(String value) {
        assertInvalidValue(() -> new TransactionDescription(value), "description", "REQUIRED");
    }

    @Test
    void acceptsMaxLength() {
        assertThat(new TransactionDescription("a".repeat(TransactionDescription.MAX_LENGTH)).value())
                .hasSize(TransactionDescription.MAX_LENGTH);
    }

    @Test
    void rejectsAboveMaxLength() {
        assertInvalidValue(() -> new TransactionDescription("a".repeat(TransactionDescription.MAX_LENGTH + 1)),
                "description", "TOO_LONG");
    }

    @ParameterizedTest
    @ValueSource(strings = { "Bistrô\tLume", "Bistrô\nLume", "Bistrô\0Lume" })
    void rejectsControlCharacters(String value) {
        assertInvalidValue(() -> new TransactionDescription(value), "description", "INVALID_CHARACTERS");
    }
}
