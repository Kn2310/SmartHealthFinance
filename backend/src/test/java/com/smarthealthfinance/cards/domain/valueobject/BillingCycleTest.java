package com.smarthealthfinance.cards.domain.valueobject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.time.YearMonth;

import static com.smarthealthfinance.shared.domain.InvalidValueAssert.assertInvalidValue;
import static org.assertj.core.api.Assertions.assertThat;

class BillingCycleTest {

    @Test
    void validatesDays() {
        assertInvalidValue(() -> new BillingCycle(0, 5), "closingDay", "OUT_OF_RANGE");
        assertInvalidValue(() -> new BillingCycle(32, 5), "closingDay", "OUT_OF_RANGE");
        assertInvalidValue(() -> new BillingCycle(28, 0), "dueDay", "OUT_OF_RANGE");
        assertInvalidValue(() -> new BillingCycle(28, 32), "dueDay", "OUT_OF_RANGE");
        assertInvalidValue(() -> new BillingCycle(10, 10), "dueDay", "SAME_AS_CLOSING");
        // Ciclo de mesmo mês com fechamento depois do dia 27 venceria junto com o fechamento em fevereiro.
        assertInvalidValue(() -> new BillingCycle(28, 30), "dueDay", "CONFLICTS_WITH_CLOSING");
        assertThat(new BillingCycle(27, 31).dueMonthOffset()).isZero();
        assertThat(new BillingCycle(31, 30).dueMonthOffset()).isEqualTo(1);
    }

    @ParameterizedTest(name = "fecha {0}, vence {1}, mês {2} → {3} / {4}")
    @CsvSource({
            // Aurora (09-dados-de-exemplo): fecha 28 set, vence 05 out.
            "28, 5, 2026-09, 2026-09-28, 2026-10-05",
            // Horizonte: fecha 05 out, vence 12 out (mesmo mês).
            "5, 12, 2026-10, 2026-10-05, 2026-10-12",
            // Dia 31 em mês de 30 dias e em fevereiro (comum e bissexto).
            "31, 10, 2026-09, 2026-09-30, 2026-10-10",
            "31, 10, 2027-02, 2027-02-28, 2027-03-10",
            "31, 10, 2028-02, 2028-02-29, 2028-03-10",
            "30, 5, 2026-12, 2026-12-30, 2027-01-05",
            "27, 31, 2027-02, 2027-02-27, 2027-02-28",
            "27, 31, 2028-02, 2028-02-27, 2028-02-29",
            // Virada de ano.
            "28, 5, 2026-12, 2026-12-28, 2027-01-05",
    })
    void computesClosingAndDueDates(int closingDay, int dueDay, String closingMonth, String closing, String due) {
        BillingCycle cycle = new BillingCycle(closingDay, dueDay);
        YearMonth month = YearMonth.parse(closingMonth);

        assertThat(cycle.closingDate(month)).isEqualTo(LocalDate.parse(closing));
        assertThat(cycle.dueDate(month)).isEqualTo(LocalDate.parse(due));
        assertThat(cycle.closingMonthOfDueMonth(cycle.dueMonth(month))).isEqualTo(month);
    }
}
