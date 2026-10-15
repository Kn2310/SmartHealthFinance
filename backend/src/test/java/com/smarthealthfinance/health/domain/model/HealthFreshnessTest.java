package com.smarthealthfinance.health.domain.model;

import com.smarthealthfinance.health.domain.enums.HealthFreshnessState;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Estados de freshness do snapshot (ADR-0018 §7). */
class HealthFreshnessTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-10-15");

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            // built, transações, sujo, pendentes, analytics atualizando, as_of → estado
            "false, false, false, 0, false, null,       UP_TO_DATE",
            "false, true,  false, 0, false, null,       NOT_BUILT",
            "true,  true,  false, 0, false, 2026-10-15, UP_TO_DATE",
            "true,  true,  true,  0, false, 2026-10-15, RECALCULATING",
            "true,  true,  false, 2, false, 2026-10-15, RECALCULATING",
            "true,  true,  false, 0, true,  2026-10-15, RECALCULATING",
            "true,  true,  false, 0, false, 2026-10-14, RECALCULATING",
    })
    void resolve(boolean built, boolean hasTransactions, boolean dirty, int pending, boolean analyticsUpdating,
                 LocalDate latestAsOf, HealthFreshnessState expected) {
        assertThat(HealthFreshness.resolve(built, hasTransactions, dirty, pending, analyticsUpdating, latestAsOf,
                TODAY, null, null).state()).isEqualTo(expected);
    }
}
