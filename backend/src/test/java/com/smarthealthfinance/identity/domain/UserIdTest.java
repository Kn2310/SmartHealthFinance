package com.smarthealthfinance.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class UserIdTest {

	@Test
	void rejectsNull() {
		assertThatNullPointerException().isThrownBy(() -> new UserId(null));
	}

	@Test
	void generatesUuidV7() {
		assertThat(UserId.generate(Instant.parse("2026-09-27T12:00:00Z")).value().version()).isEqualTo(7);
	}

	@Test
	void toStringIsTheUuid() {
		UUID uuid = UUID.randomUUID();

		assertThat(new UserId(uuid)).hasToString(uuid.toString());
	}

}
