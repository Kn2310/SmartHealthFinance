package com.smarthealthfinance.identity.domain.valueobject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static com.smarthealthfinance.shared.domain.InvalidValueAssert.assertInvalidValue;
import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceNameTest {

    @Test
    void personalDefaultIsPessoal() {
        assertThat(WorkspaceName.PERSONAL_DEFAULT.value()).isEqualTo("Pessoal");
    }

    @Test
    void stripsSurroundingWhitespace() {
        assertThat(new WorkspaceName("  Casa  ").value()).isEqualTo("Casa");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "   " })
    void rejectsMissingValue(String value) {
        assertInvalidValue(() -> new WorkspaceName(value), "name", "REQUIRED");
    }

    @Test
    void acceptsMaxLength() {
        assertThat(new WorkspaceName("a".repeat(WorkspaceName.MAX_LENGTH)).value()).hasSize(WorkspaceName.MAX_LENGTH);
    }

    @Test
    void rejectsAboveMaxLength() {
        assertInvalidValue(() -> new WorkspaceName("a".repeat(WorkspaceName.MAX_LENGTH + 1)), "name", "TOO_LONG");
    }

    @ParameterizedTest
    @ValueSource(strings = { "Casa\tPraia", "Casa\nPraia", "Casa\0Praia" })
    void rejectsControlCharacters(String value) {
        assertInvalidValue(() -> new WorkspaceName(value), "name", "INVALID_CHARACTERS");
    }
}
