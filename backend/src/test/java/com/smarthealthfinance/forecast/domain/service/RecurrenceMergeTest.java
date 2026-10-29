package com.smarthealthfinance.forecast.domain.service;

import com.smarthealthfinance.forecast.domain.enums.Confidence;
import com.smarthealthfinance.forecast.domain.enums.SeriesDirection;
import com.smarthealthfinance.forecast.domain.enums.SeriesOrigin;
import com.smarthealthfinance.forecast.domain.enums.SeriesStatus;
import com.smarthealthfinance.forecast.domain.model.RecurringSeries;
import com.smarthealthfinance.transactions.domain.enums.CashFlowRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.List;

import static com.smarthealthfinance.forecast.domain.service.ForecastFixture.brl;
import static com.smarthealthfinance.forecast.domain.service.ForecastFixture.date;
import static org.assertj.core.api.Assertions.assertThat;

/** Mescla da detecção com a série persistida (ADR-0030 §2, ADR-0031 §4): nunca altera o estado do usuário. */
class RecurrenceMergeTest {

    private static final Instant CHANGED_AT = Instant.parse("2026-09-01T10:00:00Z");

    private static RecurringSeries userSeries(SeriesStatus status) {
        RecurringSeries base = ForecastFixture.series(SeriesDirection.EXPENSE, "streamo", 10, "40.00", "38.00",
                "42.00", Confidence.MEDIUM, status);
        return new RecurringSeries(base.direction(), base.key(), base.label(), base.channel(), base.periodicity(),
                base.expectedDay(), base.expectedAmount(), base.amountRange(), base.confidence(), base.occurrences(),
                base.algorithmVersion(), status, SeriesOrigin.USER, brl("55.00"), 17, CHANGED_AT);
    }

    private static List<RecurringSeries> detectedStreamo() {
        ForecastFixture fixture = new ForecastFixture()
                .monthly(CashFlowRole.EXPENSE, "streamo", 12, "SUBSCRIPTIONS", "2026-03=41.00", "2026-04=41.00",
                        "2026-05=41.00", "2026-06=41.00", "2026-07=41.00");
        return RecurrenceDetector.detect(fixture.entries(), date("2026-09-22"));
    }

    @ParameterizedTest
    @EnumSource(SeriesStatus.class)
    void redetectionUpdatesOnlyTheDetectedFieldsAndNeverTheUserState(SeriesStatus status) {
        RecurringSeries existing = userSeries(status);
        List<RecurringSeries> merged = RecurrenceMerge.merge(List.of(existing), detectedStreamo());

        assertThat(merged).hasSize(1);
        RecurringSeries series = merged.getFirst();
        // estado do usuário intacto
        assertThat(series.status()).isEqualTo(status);
        assertThat(series.origin()).isEqualTo(SeriesOrigin.USER);
        assertThat(series.userExpectedAmount()).isEqualTo(brl("55.00"));
        assertThat(series.userExpectedDay()).isEqualTo(17);
        assertThat(series.statusChangedAt()).isEqualTo(CHANGED_AT);
        // campos detectados atualizados
        assertThat(series.expectedDay()).isEqualTo(12);
        assertThat(series.expectedAmount()).isEqualTo(brl("41.00"));
        assertThat(series.confidence()).isEqualTo(Confidence.HIGH);
        assertThat(series.occurrences()).hasSize(5);
    }

    @Test
    void anIgnoredKeyIsNotSuggestedAgain() {
        List<RecurringSeries> merged = RecurrenceMerge.merge(List.of(userSeries(SeriesStatus.IGNORED)),
                detectedStreamo());
        assertThat(merged).singleElement().extracting(RecurringSeries::status).isEqualTo(SeriesStatus.IGNORED);
    }

    @Test
    void aNewKeyEntersAsDetected() {
        List<RecurringSeries> merged = RecurrenceMerge.merge(List.of(), detectedStreamo());
        assertThat(merged).singleElement().satisfies(series -> {
            assertThat(series.status()).isEqualTo(SeriesStatus.DETECTED);
            assertThat(series.origin()).isEqualTo(SeriesOrigin.DETECTED);
        });
    }

    @Test
    void aKeyThatStopsBeingACandidateIsKeptUntouched() {
        RecurringSeries confirmed = ForecastFixture.series(SeriesDirection.EXPENSE, "academia", 3, "120.00", "118.00",
                "122.00", Confidence.HIGH, SeriesStatus.CONFIRMED);
        List<RecurringSeries> merged = RecurrenceMerge.merge(List.of(confirmed), detectedStreamo());

        assertThat(merged).hasSize(2);
        assertThat(merged).contains(confirmed);
    }

    @Test
    void mergeIsIdempotentAndOrdered() {
        RecurringSeries confirmed = ForecastFixture.series(SeriesDirection.INCOME, "empresa", 5, "5000.00", "5000.00",
                "5000.00", Confidence.HIGH, SeriesStatus.CONFIRMED);
        List<RecurringSeries> once = RecurrenceMerge.merge(List.of(confirmed), detectedStreamo());
        List<RecurringSeries> twice = RecurrenceMerge.merge(once, detectedStreamo());

        assertThat(twice).isEqualTo(once);
        assertThat(once).extracting(RecurringSeries::direction)
                .containsExactly(SeriesDirection.INCOME, SeriesDirection.EXPENSE);
    }

    @Test
    void anEditedAmountIsExactWithHighConfidence() {
        RecurringSeries series = userSeries(SeriesStatus.CONFIRMED);
        assertThat(series.effectiveAmount()).isEqualTo(brl("55.00"));
        assertThat(series.effectiveRange().min()).isEqualTo(brl("55.00"));
        assertThat(series.effectiveRange().max()).isEqualTo(brl("55.00"));
        assertThat(series.effectiveConfidence()).isEqualTo(Confidence.HIGH);
        assertThat(series.effectiveDay()).isEqualTo(17);
    }
}
