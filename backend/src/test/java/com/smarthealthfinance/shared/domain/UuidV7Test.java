package com.smarthealthfinance.shared.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

class UuidV7Test {

	private static final Instant NOW = Instant.parse("2026-09-27T12:00:00.123Z");

	@Test
	void hasVersion7AndRfcVariant() {
		UUID id = UuidV7.generate(NOW);

		assertThat(id.version()).isEqualTo(7);
		assertThat(id.variant()).isEqualTo(2);
	}

	@Test
	void encodesUnixMillisInFirst48Bits() {
		UUID id = UuidV7.generate(NOW);

		assertThat(id.getMostSignificantBits() >>> 16).isEqualTo(NOW.toEpochMilli());
	}

	@Test
	void isOrderedByTime() {
		UUID earlier = UuidV7.generate(NOW);
		UUID later = UuidV7.generate(NOW.plusMillis(1));

		assertThat(earlier.compareTo(later)).isNegative();
	}

	@Test
	void isUniqueWithinSameMillisecond() {
		Set<UUID> ids = IntStream.range(0, 10_000)
			.mapToObj(i -> UuidV7.generate(NOW))
			.collect(Collectors.toSet());

		assertThat(ids).hasSize(10_000);
	}

}
