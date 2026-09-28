package com.smarthealthfinance.shared.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

public final class InvalidValueAssert {

	private InvalidValueAssert() {
	}

	public static void assertInvalidValue(ThrowingCallable call, String field, String reason) {
		assertThatThrownBy(call).isInstanceOfSatisfying(InvalidValueException.class, ex -> {
			assertThat(ex.field()).isEqualTo(field);
			assertThat(ex.reason()).isEqualTo(reason);
		});
	}

}
