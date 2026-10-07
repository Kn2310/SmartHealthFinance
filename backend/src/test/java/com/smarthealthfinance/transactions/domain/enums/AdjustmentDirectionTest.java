package com.smarthealthfinance.transactions.domain.enums;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static com.smarthealthfinance.shared.domain.InvalidValueAssert.assertInvalidValue;
import static org.assertj.core.api.Assertions.assertThat;

class AdjustmentDirectionTest {

    @Test
    void parsesDirections() {
        assertThat(AdjustmentDirection.parseOptional("INCREASE")).contains(AdjustmentDirection.INCREASE);
        assertThat(AdjustmentDirection.parseOptional(" DECREASE ")).contains(AdjustmentDirection.DECREASE);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "  " })
    void missingValueIsAbsent(String value) {
        assertThat(AdjustmentDirection.parseOptional(value)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = { "increase", "UP", "IN" })
    void rejectsUnknownValue(String value) {
        assertInvalidValue(() -> AdjustmentDirection.parseOptional(value), "adjustmentDirection", "INVALID");
    }
}
