package com.smarthealthfinance.transactions.domain.enums;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static com.smarthealthfinance.shared.domain.InvalidValueAssert.assertInvalidValue;
import static org.assertj.core.api.Assertions.assertThat;

class TransactionStatusTest {

    @Test
    void statusesAreExactlyTheOnesFromTheSpec() {
        assertThat(TransactionStatus.values()).extracting(Enum::name)
                .containsExactly("PENDING", "POSTED", "CANCELLED", "REVERSED");
    }

    @ParameterizedTest
    @EnumSource(TransactionStatus.class)
    void parsesEveryStatus(TransactionStatus status) {
        assertThat(TransactionStatus.parse(status.name())).isEqualTo(status);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "  " })
    void rejectsMissingValue(String value) {
        assertInvalidValue(() -> TransactionStatus.parse(value), "status", "REQUIRED");
    }

    @ParameterizedTest
    @ValueSource(strings = { "posted", "CONFIRMED", "DELETED" })
    void rejectsUnknownValue(String value) {
        assertInvalidValue(() -> TransactionStatus.parse(value), "status", "INVALID");
    }

    @Test
    void onlyCancelledAndReversedAreVoided() {
        assertThat(TransactionStatus.PENDING.isVoided()).isFalse();
        assertThat(TransactionStatus.POSTED.isVoided()).isFalse();
        assertThat(TransactionStatus.CANCELLED.isVoided()).isTrue();
        assertThat(TransactionStatus.REVERSED.isVoided()).isTrue();
    }
}
