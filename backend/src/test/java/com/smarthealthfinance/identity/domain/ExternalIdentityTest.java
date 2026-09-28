package com.smarthealthfinance.identity.domain;

import static com.smarthealthfinance.shared.domain.InvalidValueAssert.assertInvalidValue;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ExternalIdentityTest {

	private static final String ISSUER = "http://issuer.test/realms/smart-health-finance";

	@Test
	void keepsIssuerAndSubjectExactly() {
		ExternalIdentity identity = new ExternalIdentity(ISSUER, "f3c1-Sub");

		assertThat(identity.issuer()).isEqualTo(ISSUER);
		assertThat(identity.subject()).isEqualTo("f3c1-Sub");
	}

	@Test
	void sameSubjectFromDifferentIssuersIsADifferentIdentity() {
		assertThat(new ExternalIdentity(ISSUER, "sub")).isNotEqualTo(new ExternalIdentity("https://other.idp", "sub"));
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "  " })
	void rejectsMissingIssuer(String issuer) {
		assertInvalidValue(() -> new ExternalIdentity(issuer, "sub"), "issuer", "REQUIRED");
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "  " })
	void rejectsMissingSubject(String subject) {
		assertInvalidValue(() -> new ExternalIdentity(ISSUER, subject), "subject", "REQUIRED");
	}

	@Test
	void acceptsUpTo255Characters() {
		assertThat(new ExternalIdentity(ISSUER, "s".repeat(255)).subject()).hasSize(255);
	}

	@Test
	void rejectsMoreThan255Characters() {
		assertInvalidValue(() -> new ExternalIdentity(ISSUER, "s".repeat(256)), "subject", "TOO_LONG");
		assertInvalidValue(() -> new ExternalIdentity("i".repeat(256), "sub"), "issuer", "TOO_LONG");
	}

}
