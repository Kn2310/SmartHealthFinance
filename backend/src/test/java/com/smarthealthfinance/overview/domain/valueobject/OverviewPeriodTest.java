package com.smarthealthfinance.overview.domain.valueobject;

import com.smarthealthfinance.overview.domain.enums.PeriodType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;

import static com.smarthealthfinance.shared.domain.InvalidValueAssert.assertInvalidValue;
import static org.assertj.core.api.Assertions.assertThat;

class OverviewPeriodTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-09-27");

    @Test
    void currentMonthRunsFromTheFirstDayUntilToday() {
        OverviewPeriod period = OverviewPeriod.resolve(PeriodType.CURRENT_MONTH, null, null, TODAY);

        assertThat(period.from()).isEqualTo(LocalDate.parse("2026-09-01"));
        assertThat(period.to()).isEqualTo(TODAY);
        assertThat(period.balanceAsOf()).isEqualTo(TODAY);
    }

    @Test
    void currentMonthOnTheFirstDayIsASingleDay() {
        LocalDate first = LocalDate.parse("2026-10-01");

        OverviewPeriod period = OverviewPeriod.resolve(PeriodType.CURRENT_MONTH, null, null, first);

        assertThat(period.from()).isEqualTo(first);
        assertThat(period.to()).isEqualTo(first);
    }

    @Test
    void previousMonthIsTheWholeCivilMonth() {
        OverviewPeriod period = OverviewPeriod.resolve(PeriodType.PREVIOUS_MONTH, null, null, TODAY);

        assertThat(period.from()).isEqualTo(LocalDate.parse("2026-08-01"));
        assertThat(period.to()).isEqualTo(LocalDate.parse("2026-08-31"));
    }

    @Test
    void previousMonthCrossesTheYearBoundary() {
        OverviewPeriod period = OverviewPeriod.resolve(PeriodType.PREVIOUS_MONTH, null, null,
                LocalDate.parse("2026-01-15"));

        assertThat(period.from()).isEqualTo(LocalDate.parse("2025-12-01"));
        assertThat(period.to()).isEqualTo(LocalDate.parse("2025-12-31"));
    }

    @Test
    void previousMonthHandlesLeapFebruary() {
        OverviewPeriod period = OverviewPeriod.resolve(PeriodType.PREVIOUS_MONTH, null, null,
                LocalDate.parse("2028-03-10"));

        assertThat(period.to()).isEqualTo(LocalDate.parse("2028-02-29"));
    }

    @Test
    void customUsesTheGivenInclusiveRange() {
        OverviewPeriod period = OverviewPeriod.resolve(PeriodType.CUSTOM, LocalDate.parse("2026-07-10"),
                LocalDate.parse("2026-09-05"), TODAY);

        assertThat(period.type()).isEqualTo(PeriodType.CUSTOM);
        assertThat(period.from()).isEqualTo(LocalDate.parse("2026-07-10"));
        assertThat(period.to()).isEqualTo(LocalDate.parse("2026-09-05"));
    }

    @Test
    void customSingleDayIsValid() {
        LocalDate day = LocalDate.parse("2026-07-10");

        assertThat(OverviewPeriod.resolve(PeriodType.CUSTOM, day, day, TODAY).from()).isEqualTo(day);
    }

    @Test
    void customRequiresBothDates() {
        assertInvalidValue(() -> OverviewPeriod.resolve(PeriodType.CUSTOM, null, TODAY, TODAY), "from", "REQUIRED");
        assertInvalidValue(() -> OverviewPeriod.resolve(PeriodType.CUSTOM, TODAY, null, TODAY), "to", "REQUIRED");
    }

    @Test
    void customRejectsReversedRange() {
        assertInvalidValue(() -> OverviewPeriod.resolve(PeriodType.CUSTOM, TODAY, TODAY.minusDays(1), TODAY),
                "to", "BEFORE_FROM");
    }

    @Test
    void customRangeIsLimitedTo366Days() {
        LocalDate from = LocalDate.parse("2026-01-01");

        assertThat(OverviewPeriod.resolve(PeriodType.CUSTOM, from, from.plusDays(365), TODAY).to())
                .isEqualTo(LocalDate.parse("2027-01-01"));
        assertInvalidValue(() -> OverviewPeriod.resolve(PeriodType.CUSTOM, from, from.plusDays(366), TODAY),
                "to", "RANGE_TOO_LARGE");
    }

    @Test
    void presetsRejectExplicitDates() {
        assertInvalidValue(() -> OverviewPeriod.resolve(PeriodType.CURRENT_MONTH, TODAY, null, TODAY),
                "from", "NOT_ALLOWED");
        assertInvalidValue(() -> OverviewPeriod.resolve(PeriodType.PREVIOUS_MONTH, null, TODAY, TODAY),
                "to", "NOT_ALLOWED");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "  " })
    void missingPeriodDefaultsToCurrentMonth(String value) {
        assertThat(PeriodType.parseOrDefault(value)).isEqualTo(PeriodType.CURRENT_MONTH);
    }

    @Test
    void parsesKnownPeriodsAndRejectsUnknown() {
        assertThat(PeriodType.parseOrDefault(" PREVIOUS_MONTH ")).isEqualTo(PeriodType.PREVIOUS_MONTH);
        assertThat(PeriodType.parseOrDefault("CUSTOM")).isEqualTo(PeriodType.CUSTOM);
        assertInvalidValue(() -> PeriodType.parseOrDefault("last_month"), "period", "INVALID");
        assertInvalidValue(() -> PeriodType.parseOrDefault("YEAR"), "period", "INVALID");
    }
}
