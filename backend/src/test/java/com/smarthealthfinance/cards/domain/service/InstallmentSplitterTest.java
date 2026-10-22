package com.smarthealthfinance.cards.domain.service;

import com.smarthealthfinance.shared.domain.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Currency;
import java.util.List;
import java.util.Random;

import static com.smarthealthfinance.shared.domain.InvalidValueAssert.assertInvalidValue;
import static org.assertj.core.api.Assertions.assertThat;

class InstallmentSplitterTest {

    private static final Currency BRL = Currency.getInstance("BRL");

    @ParameterizedTest(name = "{0} em {1}× → 1ª {2}, demais {3}")
    @CsvSource({
            "100.00, 3, 33.34, 33.33",
            "4800.00, 10, 480.00, 480.00",
            "0.05, 3, 0.03, 0.01",
            "10.00, 1, 10.00, 10.00",
            "1000.01, 48, 21.00, 20.83",
    })
    void leftoverCentsGoToTheFirstInstallment(String total, int count, String first, String others) {
        List<Money> parts = InstallmentSplitter.split(brl(total), count);

        assertThat(parts).hasSize(count);
        assertThat(parts.getFirst()).isEqualTo(brl(first));
        assertThat(parts.subList(1, count)).allSatisfy(part -> assertThat(part).isEqualTo(brl(others)));
        assertThat(parts.stream().reduce(brl("0"), Money::plus)).isEqualTo(brl(total));
    }

    @Test
    void sumAlwaysEqualsTheTotal() {
        Random random = new Random(20261022L);
        for (int i = 0; i < 2_000; i++) {
            int count = 1 + random.nextInt(InstallmentSplitter.MAX_INSTALLMENTS);
            long cents = count + random.nextInt(5_000_000);
            Money total = new Money(java.math.BigDecimal.valueOf(cents, 2), BRL);

            List<Money> parts = InstallmentSplitter.split(total, count);

            assertThat(parts.stream().reduce(brl("0"), Money::plus)).isEqualTo(total);
            assertThat(parts).allSatisfy(part -> assertThat(part.isPositive()).isTrue());
            assertThat(parts.getFirst().isGreaterThan(parts.getLast()) || parts.getFirst().equals(parts.getLast()))
                    .isTrue();
        }
    }

    @Test
    void rejectsInvalidCounts() {
        assertInvalidValue(() -> InstallmentSplitter.split(brl("100.00"), 0), "installmentCount", "OUT_OF_RANGE");
        assertInvalidValue(() -> InstallmentSplitter.split(brl("100.00"), 49), "installmentCount", "OUT_OF_RANGE");
        assertInvalidValue(() -> InstallmentSplitter.split(brl("0.02"), 3), "installmentCount",
                "TOO_MANY_FOR_AMOUNT");
    }

    private static Money brl(String amount) {
        return Money.of(amount, BRL);
    }
}
