package com.smarthealthfinance.accounts.domain.valueobject;

import static com.smarthealthfinance.shared.domain.InvalidValueAssert.assertInvalidValue;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class AccountNameTest {

    @Test
    void stripsSurroundingWhitespace() {
        assertThat(new AccountName("  Banco Aurora  ").value()).isEqualTo("Banco Aurora");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "   " })
    void rejectsMissingValue(String value) {
        assertInvalidValue(() -> new AccountName(value), "name", "REQUIRED");
    }

    @Test
    void acceptsMaxLength() {
        assertThat(new AccountName("a".repeat(AccountName.MAX_LENGTH)).value()).hasSize(AccountName.MAX_LENGTH);
    }

    @Test
    void rejectsAboveMaxLength() {
        assertInvalidValue(() -> new AccountName("a".repeat(AccountName.MAX_LENGTH + 1)), "name", "TOO_LONG");
    }

    @ParameterizedTest
    @ValueSource(strings = { "Banco\tAurora", "Banco\nAurora", "Banco\0Aurora" })
    void rejectsControlCharacters(String value) {
        assertInvalidValue(() -> new AccountName(value), "name", "INVALID_CHARACTERS");
    }
}
