package com.smarthealthfinance.shared.domain;

import org.junit.jupiter.api.Test;

import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class MoneyArithmeticTest {

    private static final Currency BRL = Currency.getInstance("BRL");
    private static final Currency USD = Currency.getInstance("USD");

    @Test
    void minusIsExactAndMayGoNegative() {
        assertThat(Money.of("0.30", BRL).minus(Money.of("0.10", BRL))).isEqualTo(Money.of("0.20", BRL));
        assertThat(Money.of("10", BRL).minus(Money.of("25.50", BRL))).isEqualTo(Money.of("-15.50", BRL));
    }

    @Test
    void negateFlipsTheSign() {
        assertThat(Money.of("86.40", BRL).negate()).isEqualTo(Money.of("-86.40", BRL));
        assertThat(Money.of("-5", BRL).negate()).isEqualTo(Money.of("5", BRL));
        assertThat(Money.zero(BRL).negate()).isEqualTo(Money.zero(BRL));
    }

    @Test
    void minusRejectsDifferentCurrencies() {
        assertThatIllegalArgumentException().isThrownBy(() -> Money.of("1", BRL).minus(Money.of("1", USD)));
    }
}
