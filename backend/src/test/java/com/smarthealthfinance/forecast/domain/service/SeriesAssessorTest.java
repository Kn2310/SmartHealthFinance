package com.smarthealthfinance.forecast.domain.service;

import com.smarthealthfinance.forecast.domain.enums.Confidence;
import com.smarthealthfinance.forecast.domain.enums.SeriesDirection;
import com.smarthealthfinance.forecast.domain.enums.SeriesEligibility;
import com.smarthealthfinance.forecast.domain.enums.SeriesStatus;
import com.smarthealthfinance.forecast.domain.model.RecurringSeries;
import com.smarthealthfinance.forecast.domain.model.SeriesAssessment;
import com.smarthealthfinance.transactions.domain.enums.CashFlowRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static com.smarthealthfinance.forecast.domain.service.ForecastFixture.date;
import static org.assertj.core.api.Assertions.assertThat;

/** Elegibilidade, STALE e ocorrência no ciclo (ADR-0030 §2). */
class SeriesAssessorTest {

    private static final LocalDate TODAY = date("2026-09-22");

    private static RecurringSeries series(SeriesStatus status, Confidence confidence, int day) {
        return ForecastFixture.series(SeriesDirection.EXPENSE, "streamo", day, "40.00", "38.00", "42.00", confidence,
                status);
    }

    private static ForecastFixture history() {
        return new ForecastFixture()
                .monthly(CashFlowRole.EXPENSE, "streamo", 10, "SUBSCRIPTIONS", "2026-06=40.00", "2026-07=40.00",
                        "2026-08=40.00");
    }

    private static SeriesAssessment assess(RecurringSeries series, ForecastFixture fixture, boolean stillCandidate) {
        List<RecurringSeries> candidates = stillCandidate ? List.of(series) : List.of();
        return SeriesAssessor.assess(List.of(series), candidates, fixture.entries(), TODAY).getFirst();
    }

    /** status, confiança, ainda candidata → elegibilidade. */
    @ParameterizedTest(name = "{0} {1} candidata={2} → {3}")
    @CsvSource({
            "CONFIRMED, LOW,    true,  ELIGIBLE",
            "CONFIRMED, HIGH,   false, ELIGIBLE",
            "DETECTED,  HIGH,   true,  ELIGIBLE",
            "DETECTED,  MEDIUM, true,  ELIGIBLE",
            "DETECTED,  LOW,    true,  NOT_ELIGIBLE",
            "DETECTED,  HIGH,   false, NOT_ELIGIBLE",
            "IGNORED,   HIGH,   true,  NOT_ELIGIBLE",
    })
    void eligibility(SeriesStatus status, Confidence confidence, boolean candidate, SeriesEligibility expected) {
        assertThat(assess(series(status, confidence, 10), history(), candidate).eligibility()).isEqualTo(expected);
    }

    @Test
    void aConfirmedSeriesWithoutOccurrenceInThreeMonthsIsStale() {
        ForecastFixture old = new ForecastFixture()
                .monthly(CashFlowRole.EXPENSE, "streamo", 10, "SUBSCRIPTIONS", "2026-03=40.00", "2026-04=40.00",
                        "2026-05=40.00");
        RecurringSeries confirmed = series(SeriesStatus.CONFIRMED, Confidence.HIGH, 10);

        assertThat(assess(confirmed, old, true).eligibility()).isEqualTo(SeriesEligibility.STALE);
        // volta a ocorrer: sai de STALE
        old.add("2026-09-10", CashFlowRole.EXPENSE, "SUBSCRIPTIONS", "streamo", "40.00");
        assertThat(assess(confirmed, old, true).eligibility()).isEqualTo(SeriesEligibility.ELIGIBLE);
    }

    @Test
    void anOccurrenceInTheOldestOfTheThreeMonthsKeepsItAlive() {
        ForecastFixture fixture = new ForecastFixture().add("2026-06-01", CashFlowRole.EXPENSE, "SUBSCRIPTIONS",
                "streamo", "40.00");
        assertThat(assess(series(SeriesStatus.CONFIRMED, Confidence.HIGH, 10), fixture, true).eligibility())
                .isEqualTo(SeriesEligibility.ELIGIBLE);
        ForecastFixture tooOld = new ForecastFixture().add("2026-05-31", CashFlowRole.EXPENSE, "SUBSCRIPTIONS",
                "streamo", "40.00");
        assertThat(assess(series(SeriesStatus.CONFIRMED, Confidence.HIGH, 10), tooOld, true).eligibility())
                .isEqualTo(SeriesEligibility.STALE);
    }

    /** Dia esperado 20, hoje 22/09: uma transação em set/DD está realizada se |DD − 20| ≤ 3. */
    @ParameterizedTest(name = "transação no dia {0} → realizada={1}")
    @CsvSource({"16, false", "17, true", "20, true", "22, true", "23, false"})
    void realizedWithinThreeDaysOfTheExpectedDay(int day, boolean realized) {
        ForecastFixture fixture = new ForecastFixture().add("2026-09-%02d".formatted(day), CashFlowRole.EXPENSE,
                "SUBSCRIPTIONS", "streamo", "40.00");
        RecurringSeries series = series(SeriesStatus.CONFIRMED, Confidence.HIGH, 20);

        assertThat(assess(series, fixture, true).realizedThisMonth()).isEqualTo(day <= 22 && realized);
    }

    @Test
    void aTransactionAfterTodayIsNotRealized() {
        ForecastFixture fixture = new ForecastFixture().add("2026-09-23", CashFlowRole.EXPENSE, "SUBSCRIPTIONS",
                "streamo", "40.00");
        assertThat(assess(series(SeriesStatus.CONFIRMED, Confidence.HIGH, 23), fixture, true).realizedThisMonth())
                .isFalse();
    }

    @Test
    void sameKeyButOppositeDirectionDoesNotRealizeTheSeries() {
        ForecastFixture fixture = new ForecastFixture().add("2026-09-10", CashFlowRole.INCOME, "OTHER_INCOME",
                "streamo", "40.00");
        assertThat(assess(series(SeriesStatus.CONFIRMED, Confidence.HIGH, 10), fixture, true).realizedThisMonth())
                .isFalse();
    }

    @Test
    void shorterMonthsUseTheLastDay() {
        RecurringSeries series = series(SeriesStatus.CONFIRMED, Confidence.HIGH, 31);
        assertThat(series.expectedDateIn(YearMonth.of(2026, 2))).isEqualTo(date("2026-02-28"));
        assertThat(series.expectedDateIn(YearMonth.of(2028, 2))).isEqualTo(date("2028-02-29"));
        assertThat(series.expectedDateIn(YearMonth.of(2026, 9))).isEqualTo(date("2026-09-30"));
        assertThat(series.expectedDateIn(YearMonth.of(2026, 10))).isEqualTo(date("2026-10-31"));
    }

    @Test
    void theUserEditedDayMovesTheExpectedDate() {
        RecurringSeries base = series(SeriesStatus.CONFIRMED, Confidence.HIGH, 10);
        RecurringSeries edited = new RecurringSeries(base.direction(), base.key(), base.label(), base.channel(),
                base.periodicity(), base.expectedDay(), base.expectedAmount(), base.amountRange(), base.confidence(),
                base.occurrences(), base.algorithmVersion(), base.status(), base.origin(), null, 25, null);
        assertThat(edited.expectedDateIn(YearMonth.of(2026, 9))).isEqualTo(date("2026-09-25"));
    }
}
