package com.smarthealthfinance.accounts.domain.valueobject;

import static com.smarthealthfinance.shared.domain.InvalidValueAssert.assertInvalidValue;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class InstitutionNameTest {

    @Test
    void stripsSurroundingWhitespace() {
        assertThat(new InstitutionName("  Banco Norte ").value()).isEqualTo("Banco Norte");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "   " })
    void constructorRejectsMissingValue(String value) {
        assertInvalidValue(() -> new InstitutionName(value), "institutionName", "REQUIRED");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "   " })
    void ofNullableTreatsMissingValueAsAbsent(String value) {
        assertThat(InstitutionName.ofNullable(value)).isEmpty();
    }

    @Test
    void ofNullableKeepsValidValue() {
        assertThat(InstitutionName.ofNullable(" Banco Norte ")).contains(new InstitutionName("Banco Norte"));
    }

    @Test
    void rejectsAboveMaxLength() {
        assertInvalidValue(() -> new InstitutionName("a".repeat(InstitutionName.MAX_LENGTH + 1)), "institutionName",
                "TOO_LONG");
    }

    @Test
    void rejectsControlCharacters() {
        assertInvalidValue(() -> new InstitutionName("Banco\nNorte"), "institutionName", "INVALID_CHARACTERS");
    }
}
