package com.smarthealthfinance.analytics.domain.model;

import com.smarthealthfinance.analytics.domain.enums.ComparisonBase;
import com.smarthealthfinance.analytics.domain.enums.ComparisonState;
import com.smarthealthfinance.analytics.domain.enums.Trend;
import com.smarthealthfinance.analytics.domain.valueobject.AnalysisPeriod;
import com.smarthealthfinance.analytics.domain.valueobject.AnalyticsPeriod;
import com.smarthealthfinance.shared.domain.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Currency;
import java.util.Optional;

import static com.smarthealthfinance.shared.domain.InvalidValueAssert.assertInvalidValue;
import static org.assertj.core.api.Assertions.assertThat;

/** Período, recorte de dias, bases de comparação e variação (ADR-0015 §2–§4). */
class AnalysisRulesTest {

    private static final Currency BRL = Currency.getInstance("BRL");
    private static final LocalDate SEP_22 = LocalDate.parse("2026-09-22");

    // --- período ---

    @Test
    void currentMonthIsPartialUntilToday() {
        AnalysisPeriod period = AnalysisPeriod.resolve(null, SEP_22);

        assertThat(period.month()).isEqualTo(YearMonth.of(2026, 9));
        assertThat(period.from()).isEqualTo(LocalDate.parse("2026-09-01"));
        assertThat(period.to()).isEqualTo(SEP_22);
        assertThat(period.partial()).isTrue();
        assertThat(period.daysElapsed()).isEqualTo(22);
        assertThat(period.daysInMonth()).isEqualTo(30);
    }

    @Test
    void lastDayOfTheMonthIsNoLongerPartialAndPastMonthsAreWhole() {
        assertThat(AnalysisPeriod.resolve(null, LocalDate.parse("2026-09-30")).partial()).isFalse();

        AnalysisPeriod august = AnalysisPeriod.resolve("2026-08", SEP_22);
        assertThat(august.partial()).isFalse();
        assertThat(august.to()).isEqualTo(LocalDate.parse("2026-08-31"));
    }

    @Test
    void rejectsFutureAndMalformedMonths() {
        assertInvalidValue(() -> AnalysisPeriod.resolve("2026-10", SEP_22), "month", "FUTURE");
        assertInvalidValue(() -> AnalysisPeriod.resolve("2026-13", SEP_22), "month", "INVALID");
        assertInvalidValue(() -> AnalysisPeriod.resolve("set/2026", SEP_22), "month", "INVALID");
        assertInvalidValue(() -> AnalysisPeriod.resolve("2026-9", SEP_22), "month", "INVALID");
    }

    @Test
    void partialMonthIsComparedWithTheSameDaysClampedToEachMonth() {
        AnalysisPeriod march31 = AnalysisPeriod.resolve(null, LocalDate.parse("2027-03-30"));

        assertThat(march31.window(YearMonth.of(2027, 2)))
                .isEqualTo(new AnalyticsPeriod(LocalDate.parse("2027-02-01"), LocalDate.parse("2027-02-28")));
        assertThat(AnalysisPeriod.resolve(null, SEP_22).window(YearMonth.of(2026, 8)))
                .isEqualTo(new AnalyticsPeriod(LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-22")));
        assertThat(AnalysisPeriod.resolve("2026-08", SEP_22).window(YearMonth.of(2026, 7)))
                .isEqualTo(AnalyticsPeriod.of(YearMonth.of(2026, 7)));
    }

    // --- bases de comparação ---

    @Test
    void averageUsesOnlyMonthsSinceTheFirstActivity() {
        AnalysisPeriod period = AnalysisPeriod.resolve(null, SEP_22);

        ComparisonPlan full = ComparisonPlan.of(period, ComparisonBase.AVERAGE, null,
                Optional.of(LocalDate.parse("2026-01-15")));
        assertThat(full.averageMonths()).containsExactly(YearMonth.of(2026, 4), YearMonth.of(2026, 5),
                YearMonth.of(2026, 6), YearMonth.of(2026, 7), YearMonth.of(2026, 8));
        assertThat(full.averageWindows().getFirst())
                .isEqualTo(new AnalyticsPeriod(LocalDate.parse("2026-04-01"), LocalDate.parse("2026-04-22")));
        assertThat(full.averageState()).isEqualTo(ComparisonState.AVAILABLE);

        ComparisonPlan recent = ComparisonPlan.of(period, ComparisonBase.AVERAGE, null,
                Optional.of(LocalDate.parse("2026-08-10")));
        assertThat(recent.averageMonths()).containsExactly(YearMonth.of(2026, 8));
        assertThat(recent.averageState()).isEqualTo(ComparisonState.LIMITED);
        assertThat(recent.hasPrevious()).isTrue();

        ComparisonPlan newcomer = ComparisonPlan.of(period, ComparisonBase.PREVIOUS, null,
                Optional.of(LocalDate.parse("2026-09-03")));
        assertThat(newcomer.hasPrevious()).isFalse();
        assertThat(newcomer.averageState()).isEqualTo(ComparisonState.INSUFFICIENT_DATA);
        assertThat(newcomer.selectedAvailable()).isFalse();

        assertThat(ComparisonPlan.of(period, ComparisonBase.PREVIOUS, 3, Optional.empty()).hasAverage()).isFalse();
    }

    @Test
    void validatesComparisonParameters() {
        AnalysisPeriod period = AnalysisPeriod.resolve(null, SEP_22);

        assertInvalidValue(() -> ComparisonPlan.of(period, ComparisonBase.AVERAGE, 0, Optional.empty()),
                "averageMonths", "OUT_OF_RANGE");
        assertInvalidValue(() -> ComparisonPlan.of(period, ComparisonBase.AVERAGE, 13, Optional.empty()),
                "averageMonths", "OUT_OF_RANGE");
        assertInvalidValue(() -> ComparisonPlan.parseBase("MEDIA"), "compareWith", "INVALID");
        assertThat(ComparisonPlan.parseBase(null)).isEqualTo(ComparisonBase.PREVIOUS);
        assertThat(ComparisonPlan.parseBase("AVERAGE")).isEqualTo(ComparisonBase.AVERAGE);
    }

    // --- variação ---

    @ParameterizedTest(name = "{0} vs {1} -> {2}% {3}")
    @CsvSource({
            // Valores do design/specs/09-dados-de-exemplo.md
            "6800.00, 6500.00, 4.6, STABLE",
            "3846.20, 3372.10, 14.1, UP",
            "1284.00, 973.00, 32.0, UP",
            "296.08, 321.83, -8.0, DOWN",
            // Limiar de 5%, sem arredondar: 4,96% é "Estável" mesmo que o percentual exibido seja 5,0
            "104.96, 100.00, 5.0, STABLE",
            "105.00, 100.00, 5.0, UP",
            "95.00, 100.00, -5.0, DOWN",
            "100.00, 100.00, 0.0, STABLE",
            "0.00, 100.00, -100.0, GONE",
    })
    void classifiesVariations(String current, String base, String percent, Trend trend) {
        Variation variation = Variation.of(Money.of(current, BRL), Money.of(base, BRL));

        assertThat(variation.percent()).hasToString(percent);
        assertThat(variation.trend()).isEqualTo(trend);
        assertThat(variation.difference()).isEqualTo(Money.of(current, BRL).minus(Money.of(base, BRL)));
    }

    @Test
    void zeroBaseHasNoPercent() {
        assertThat(Variation.of(Money.of("300.00", BRL), Money.zero(BRL)).trend()).isEqualTo(Trend.NEW);
        assertThat(Variation.of(Money.of("300.00", BRL), Money.zero(BRL)).percent()).isNull();
        assertThat(Variation.of(Money.zero(BRL), Money.zero(BRL)).trend()).isEqualTo(Trend.STABLE);
        assertThat(Variation.of(Money.of("1.00", BRL), null)).isNull();
    }
}
