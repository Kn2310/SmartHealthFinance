package com.smarthealthfinance.transactions.domain.enums;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static com.smarthealthfinance.shared.domain.InvalidValueAssert.assertInvalidValue;
import static org.assertj.core.api.Assertions.assertThat;

class TransactionTypeTest {

    /** Lista fechada da spec 05.4 — mudar exige migration (check no banco) e revisão do ADR-0005. */
    @Test
    void typesAreExactlyTheOnesFromTheSpec() {
        assertThat(TransactionType.values()).extracting(Enum::name)
                .containsExactly("INCOME", "EXPENSE", "TRANSFER", "ADJUSTMENT", "REFUND");
    }

    @ParameterizedTest
    @EnumSource(TransactionType.class)
    void parsesEveryType(TransactionType type) {
        assertThat(TransactionType.parse(type.name())).isEqualTo(type);
    }

    @Test
    void ignoresSurroundingWhitespace() {
        assertThat(TransactionType.parse(" EXPENSE ")).isEqualTo(TransactionType.EXPENSE);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "  " })
    void rejectsMissingValue(String value) {
        assertInvalidValue(() -> TransactionType.parse(value), "type", "REQUIRED");
    }

    @ParameterizedTest
    @ValueSource(strings = { "expense", "PAYMENT", "CREDIT" })
    void rejectsUnknownValue(String value) {
        assertInvalidValue(() -> TransactionType.parse(value), "type", "INVALID");
    }
}
