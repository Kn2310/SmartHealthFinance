package com.smarthealthfinance.identity.domain;

import static com.smarthealthfinance.shared.domain.InvalidValueAssert.assertInvalidValue;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class EmailTest {

	@ParameterizedTest
	@ValueSource(strings = { "ana@example.com", "user.smart@sub.domain.com.br", "s+tag@s.io" })
	void acceptsValidAddresses(String value) {
		assertThat(new Email(value).value()).isEqualTo(value);
	}

	@Test
	void normalizesCaseAndSurroundingWhitespace() {
		assertThat(new Email("  Ana.Silva@Example.COM ").value()).isEqualTo("ana.silva@example.com");
	}

	@Test
	void equalityUsesNormalizedValue() {
		assertThat(new Email("ANA@example.com")).isEqualTo(new Email("ana@example.com"));
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "   " })
	void rejectsMissingValue(String value) {
		assertInvalidValue(() -> new Email(value), "email", "REQUIRED");
	}

	@ParameterizedTest
	@ValueSource(strings = { "ana", "ana@", "@example.com", "ana@example", "ana silva@example.com",
			"ana@@example.com" })
	void rejectsInvalidFormat(String value) {
		assertInvalidValue(() -> new Email(value), "email", "INVALID_FORMAT");
	}

	@Test
	void acceptsUpTo254CharactersMatchingTheColumn() {
		String address = "a".repeat(242) + "@example.com";

		assertThat(new Email(address).value()).hasSize(254);
	}

	@Test
	void rejectsMoreThan254Characters() {
		String address = "a".repeat(243) + "@example.com";

		assertInvalidValue(() -> new Email(address), "email", "TOO_LONG");
	}

	@Test
	void toStringMasksLocalPart() {
		Email email = new Email("ana.silva@example.com");

		assertThat(email.toString()).isEqualTo("a***@example.com").doesNotContain("silva");
	}

}
