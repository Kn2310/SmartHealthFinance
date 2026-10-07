package com.smarthealthfinance.shared.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.Currency;

import static com.smarthealthfinance.shared.domain.InvalidValueAssert.assertInvalidValue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class MoneyTest {

    private static final Currency BRL = Currency.getInstance("BRL");
    private static final Currency USD = Currency.getInstance("USD");

    @Test
    void equivalentAmountsAreEqualRegardlessOfScale() {
        assertThat(Money.of("86.4", BRL)).isEqualTo(Money.of("86.40", BRL)).isEqualTo(Money.of("86.4000", BRL));
        assertThat(new Money(new BigDecimal("10"), BRL)).isEqualTo(Money.of("10.00", BRL));
    }

    @Test
    void storesFourDecimalPlaces() {
        assertThat(Money.of("86.4", BRL).amount()).isEqualByComparingTo("86.40").hasScaleOf(Money.SCALE);
    }

    @Test
    void plainStringUsesAtLeastTheCurrencyFractionDigits() {
        assertThat(Money.of("86.4", BRL).toPlainString()).isEqualTo("86.40");
        assertThat(Money.of("100", BRL).toPlainString()).isEqualTo("100.00");
        assertThat(Money.of("1.2345", BRL).toPlainString()).isEqualTo("1.2345");
        assertThat(Money.of("-3.5", BRL).toPlainString()).isEqualTo("-3.50");
    }

    @Test
    void reportsSignificantDecimalPlaces() {
        assertThat(Money.of("86.40", BRL).decimalPlaces()).isEqualTo(1);
        assertThat(Money.of("100.00", BRL).decimalPlaces()).isZero();
        assertThat(Money.of("0.005", BRL).decimalPlaces()).isEqualTo(3);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "  " })
    void rejectsMissingAmount(String value) {
        assertInvalidValue(() -> Money.of(value, BRL), "amount", "REQUIRED");
    }

    @ParameterizedTest
    @ValueSource(strings = { "abc", "1e3", "1,50", "R$ 10", "-", "10.", ".5", "+5", "1 000" })
    void rejectsMalformedAmount(String value) {
        assertInvalidValue(() -> Money.of(value, BRL), "amount", "INVALID_FORMAT");
    }

    @Test
    void rejectsMoreThanFourDecimalsInsteadOfRounding() {
        assertInvalidValue(() -> Money.of("0.00001", BRL), "amount", "TOO_MANY_DECIMALS");
        assertInvalidValue(() -> new Money(new BigDecimal("0.00001"), BRL), "amount", "TOO_MANY_DECIMALS");
    }

    @Test
    void trailingZerosBeyondFourDecimalsAreNotPrecision() {
        assertThat(Money.of("1.500000", BRL)).isEqualTo(Money.of("1.5", BRL));
    }

    @Test
    void acceptsFifteenIntegerDigitsAndRejectsMore() {
        assertThat(Money.of("999999999999999.9999", BRL).amount()).isEqualByComparingTo("999999999999999.9999");
        assertInvalidValue(() -> Money.of("1000000000000000", BRL), "amount", "TOO_LARGE");
    }

    @Test
    void requiresCurrency() {
        assertThatNullPointerException().isThrownBy(() -> Money.of("1", null));
        assertThatNullPointerException().isThrownBy(() -> new Money(BigDecimal.ONE, null));
    }

    @Test
    void addsAmountsOfTheSameCurrency() {
        assertThat(Money.of("60.10", BRL).plus(Money.of("39.90", BRL))).isEqualTo(Money.of("100", BRL));
        assertThat(Money.zero(BRL).plus(Money.of("0.01", BRL))).isEqualTo(Money.of("0.01", BRL));
    }

    @Test
    void neverMixesCurrencies() {
        assertThatIllegalArgumentException().isThrownBy(() -> Money.of("1", BRL).plus(Money.of("1", USD)));
        assertThatIllegalArgumentException().isThrownBy(() -> Money.of("1", BRL).isGreaterThan(Money.of("1", USD)));
    }

    @Test
    void comparesAmounts() {
        assertThat(Money.of("0.01", BRL).isPositive()).isTrue();
        assertThat(Money.zero(BRL).isPositive()).isFalse();
        assertThat(Money.of("-1", BRL).isPositive()).isFalse();
        assertThat(Money.of("100.01", BRL).isGreaterThan(Money.of("100", BRL))).isTrue();
        assertThat(Money.of("100.00", BRL).isGreaterThan(Money.of("100", BRL))).isFalse();
    }

    /** Ponto flutuante perderia o centavo: 0.1 + 0.2 != 0.3 em double. */
    @Test
    void sumIsExact() {
        assertThat(Money.of("0.1", BRL).plus(Money.of("0.2", BRL))).isEqualTo(Money.of("0.3", BRL));
    }
}
