package com.smarthealthfinance.identity.domain;

import static com.smarthealthfinance.shared.domain.InvalidValueAssert.assertInvalidValue;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class DisplayNameTest {

	@Test
	void stripsSurroundingWhitespace() {
		assertThat(new DisplayName("  Ana Silva  ").value()).isEqualTo("Ana Silva");
	}

	@ParameterizedTest
	@ValueSource(strings = { "José Ñandú", "Ana 🌱", "O'Connor-Silva" })
	void acceptsUnicodeNames(String value) {
		assertThat(new DisplayName(value).value()).isEqualTo(value);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "   " })
	void rejectsMissingValue(String value) {
		assertInvalidValue(() -> new DisplayName(value), "displayName", "REQUIRED");
	}

	@Test
	void acceptsMaxLengthAfterStripping() {
		String value = " " + "a".repeat(DisplayName.MAX_LENGTH) + " ";

		assertThat(new DisplayName(value).value()).hasSize(DisplayName.MAX_LENGTH);
	}

	@Test
	void rejectsAboveMaxLength() {
		assertInvalidValue(() -> new DisplayName("a".repeat(DisplayName.MAX_LENGTH + 1)), "displayName", "TOO_LONG");
	}

	@ParameterizedTest
	@ValueSource(strings = { "Ana\tSilva", "Ana\nSilva", "Ana\0Silva" })
	void rejectsControlCharacters(String value) {
		assertInvalidValue(() -> new DisplayName(value), "displayName", "INVALID_CHARACTERS");
	}

}
