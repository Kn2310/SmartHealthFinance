package com.smarthealthfinance.ingestion.infrastructure.parser;

import com.smarthealthfinance.ingestion.domain.exception.StatementRejectedException;
import com.smarthealthfinance.ingestion.infrastructure.parser.AmountText.DecimalStyle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static com.smarthealthfinance.shared.domain.InvalidValueAssert.assertInvalidValue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AmountTextTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "-86,40|-86.40",
            "1.234,56|1234.56",
            "-1.234.567,89|-1234567.89",
            "R$ 1.234,56|1234.56",
            "-R$ 12,5|-12.5",
            "12,50-|-12.50",
            "+4500|4500",
            "1.234.567|1234567",
    })
    void parsesBrazilianStyle(String raw, String expected) {
        assertThat(AmountText.parse(raw, Optional.of(DecimalStyle.COMMA))).isEqualByComparingTo(expected);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "-86.40|-86.40",
            "1,234.56|1234.56",
            "1234.5|1234.5",
            "1,234,567|1234567",
    })
    void parsesDotStyle(String raw, String expected) {
        assertThat(AmountText.parse(raw, Optional.of(DecimalStyle.DOT))).isEqualByComparingTo(expected);
    }

    @Test
    void ambiguousValueFollowsTheFileStyle() {
        assertThat(AmountText.parse("1.234", Optional.of(DecimalStyle.COMMA))).isEqualByComparingTo("1234");
        assertThat(AmountText.parse("1.234", Optional.of(DecimalStyle.DOT))).isEqualByComparingTo("1.234");
    }

    @Test
    void ambiguousValueWithoutFileStyleIsRejected() {
        assertInvalidValue(() -> AmountText.parse("1.234", Optional.empty()), "amount", "AMBIGUOUS_AMOUNT");
    }

    @Test
    void neverUsesFloatingPoint() {
        BigDecimal value = AmountText.parse("0,10", Optional.empty());

        assertThat(value).isEqualTo(new BigDecimal("0.10"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "  ", "abc", "12a", "1,2,3.4.5", "--1", "1.23.4", ",50", "1e3" })
    void rejectsInvalidValues(String raw) {
        assertInvalidValue(() -> AmountText.parse(raw, Optional.of(DecimalStyle.COMMA)), "amount", "INVALID_AMOUNT");
    }

    @Test
    void infersStyleFromUnambiguousValues() {
        assertThat(AmountText.inferStyle(List.of("1.234", "-86,40", "10"))).contains(DecimalStyle.COMMA);
        assertThat(AmountText.inferStyle(List.of("1,234", "-86.40"))).contains(DecimalStyle.DOT);
        assertThat(AmountText.inferStyle(List.of("1.234", "10", "lixo"))).isEmpty();
    }

    @Test
    void rejectsFileMixingStyles() {
        assertThatThrownBy(() -> AmountText.inferStyle(List.of("-86,40", "12.50")))
                .isInstanceOfSatisfying(StatementRejectedException.class,
                        ex -> assertThat(ex.reason()).isEqualTo("INCONSISTENT_DECIMAL_SEPARATOR"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = { "-86.40|-86.40", "-86,40|-86.40", "4500|4500", "+.5|0.5", "-1.234|-1.234" })
    void parsesOfxAmounts(String raw, String expected) {
        assertThat(AmountText.parseOfx(raw)).isEqualByComparingTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = { "1.234,56", "1,234.56", "abc", "" })
    void rejectsOfxAmountsWithGrouping(String raw) {
        assertInvalidValue(() -> AmountText.parseOfx(raw), "amount", "INVALID_AMOUNT");
    }
}
