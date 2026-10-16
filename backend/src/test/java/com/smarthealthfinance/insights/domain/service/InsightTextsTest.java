package com.smarthealthfinance.insights.domain.service;

import com.smarthealthfinance.insights.domain.valueobject.EvaluationPeriod;
import com.smarthealthfinance.shared.domain.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Arredondamento e formatação dos textos (ADR-0021 §4) e o período avaliado (§1). */
class InsightTextsTest {

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', value = {
            "50.00    | R$ 50",
            "54.99    | R$ 50",
            "55.00    | R$ 60",
            "45.00    | R$ 40",
            "311.00   | R$ 310",
            "315.00   | R$ 320",
            "999.99   | R$ 1.000",
            "1000.00  | R$ 1.000",
            "1274.99  | R$ 1.250",
            "1275.00  | R$ 1.300",
            "9999.99  | R$ 10.000",
            "10050.00 | R$ 10.000",
            "15152.00 | R$ 15.200"})
    void approximateValuesRoundToTheStepOfTheirRange(String amount, String expected) {
        assertThat(InsightTexts.approx(Money.of(amount, InsightFixture.BRL))).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} ÷ {1} → {2}%")
    @CsvSource({"311, 973, 32", "1, 8, 12", "3, 8, 38", "5, 200, 2", "15, 1000, 2", "25, 1000, 2", "35, 1000, 4"})
    void titlePercentIsTheIntegerOfTheExactRatioHalfEven(String part, String whole, String expected) {
        assertThat(InsightTexts.percent(new BigDecimal(part), new BigDecimal(whole))).isEqualTo(expected);
    }

    @Test
    void oneDecimalMonthsAndRanges() {
        assertThat(InsightTexts.oneDecimal(new BigDecimal("2.39"))).isEqualTo("2,4");
        assertThat(InsightTexts.oneDecimal(new BigDecimal("2.25"))).isEqualTo("2,2");
        assertThat(InsightTexts.monthName(YearMonth.of(2026, 3))).isEqualTo("março");
        assertThat(InsightTexts.capitalizedMonthName(YearMonth.of(2026, 9))).isEqualTo("Setembro");
        assertThat(InsightTexts.range(List.of(YearMonth.of(2026, 4), YearMonth.of(2026, 8)))).isEqualTo("abr–ago");
        assertThat(InsightTexts.range(List.of(YearMonth.of(2025, 11), YearMonth.of(2026, 3))))
                .isEqualTo("nov/2025–mar/2026");
        assertThat(InsightTexts.range(List.of(YearMonth.of(2026, 8)))).isEqualTo("ago");
        assertThat(InsightTexts.dayMonth(LocalDate.parse("2026-09-05"))).isEqualTo("5 set");
        assertThat(InsightTexts.inPeriod(YearMonth.of(2026, 9), true)).isEqualTo("neste mês");
        assertThat(InsightTexts.inPeriod(YearMonth.of(2026, 9), false)).isEqualTo("em setembro");
        assertThat(InsightTexts.lowerFirst("Alimentação fora")).isEqualTo("alimentação fora");
        assertThat(InsightTexts.count(1, "compra", "compras")).isEqualTo("1 compra");
        assertThat(InsightTexts.count(4, "compra", "compras")).isEqualTo("4 compras");
    }

    @ParameterizedTest(name = "hoje {0} → P = {1} (parcial: {2})")
    @CsvSource({
            "2026-10-01, 2026-09, false",
            "2026-10-06, 2026-09, false",
            "2026-10-07, 2026-10, true",
            "2026-10-31, 2026-10, false",
            "2026-01-03, 2025-12, false"})
    void evaluatedPeriodSwitchesOnTheSeventhDay(String today, String month, boolean partial) {
        EvaluationPeriod period = EvaluationPeriod.of(LocalDate.parse(today));
        assertThat(period.key()).isEqualTo(month);
        assertThat(period.partial()).isEqualTo(partial);
    }
}
