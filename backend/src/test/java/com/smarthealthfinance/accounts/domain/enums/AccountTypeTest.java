package com.smarthealthfinance.accounts.domain.enums;

//com.smarthealthfinance.accounts.domain.enums

import static com.smarthealthfinance.shared.domain.InvalidValueAssert.assertInvalidValue;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class AccountTypeTest {

    @ParameterizedTest
    @EnumSource(AccountType.class)
    void parsesEveryType(AccountType type) {
        assertThat(AccountType.parse(type.name())).isEqualTo(type);
    }

    @Test
    void ignoresSurroundingWhitespace() {
        assertThat(AccountType.parse(" SAVINGS ")).isEqualTo(AccountType.SAVINGS);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "  " })
    void rejectsMissingValue(String value) {
        assertInvalidValue(() -> AccountType.parse(value), "type", "REQUIRED");
    }

    @ParameterizedTest
    @ValueSource(strings = { "checking", "CREDIT_CARD", "INVESTMENT" })
    void rejectsUnknownValue(String value) {
        assertInvalidValue(() -> AccountType.parse(value), "type", "INVALID");
    }
}
