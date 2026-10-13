package com.smarthealthfinance.analytics.domain.model;

import com.smarthealthfinance.analytics.domain.enums.ProjectionState;
import com.smarthealthfinance.analytics.domain.valueobject.AnalyticsPeriod;
import com.smarthealthfinance.shared.domain.InvalidValueException;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.enums.CashFlowRole;
import com.smarthealthfinance.transactions.domain.enums.TransactionType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalyticsDomainTest {

    private static final Currency BRL = Currency.getInstance("BRL");

    @Test
    void netIsIncomePlusRefundsMinusGrossExpense() {
        FlowTotals totals = new FlowTotals(Money.of("6800.00", BRL), Money.of("3846.20", BRL),
                Money.of("30.00", BRL), 2, Money.of("500.00", BRL));

        assertThat(totals.net()).isEqualTo(Money.of("2983.80", BRL));
        assertThat(FlowTotals.empty(BRL).net()).isEqualTo(Money.zero(BRL));
    }

    @Test
    void cashFlowRoleFollowsAdr0006() {
        assertThat(CashFlowRole.of(TransactionType.INCOME)).isEqualTo(CashFlowRole.INCOME);
        assertThat(CashFlowRole.of(TransactionType.EXPENSE)).isEqualTo(CashFlowRole.EXPENSE);
        assertThat(CashFlowRole.of(TransactionType.REFUND)).isEqualTo(CashFlowRole.REFUND);
        assertThat(CashFlowRole.of(TransactionType.TRANSFER)).isEqualTo(CashFlowRole.TRANSFER);
        assertThat(CashFlowRole.of(TransactionType.ADJUSTMENT)).isEqualTo(CashFlowRole.NONE);
    }

    @Test
    void freshnessStates() {
        Instant at = Instant.parse("2026-09-27T12:00:00Z");

        assertThat(ProjectionFreshness.resolve(true, true, at, 0).state()).isEqualTo(ProjectionState.UP_TO_DATE);
        assertThat(ProjectionFreshness.resolve(true, true, at, 3).state()).isEqualTo(ProjectionState.UPDATING);
        assertThat(ProjectionFreshness.resolve(false, true, null, 0).state()).isEqualTo(ProjectionState.NOT_BUILT);
        // Sem transação lançada não há nada a projetar.
        assertThat(ProjectionFreshness.resolve(false, false, null, 0).state()).isEqualTo(ProjectionState.UP_TO_DATE);
    }

    @Test
    void periodIsInclusiveAndOrdered() {
        AnalyticsPeriod september = AnalyticsPeriod.of(YearMonth.of(2026, 9));

        assertThat(september.from()).isEqualTo(LocalDate.parse("2026-09-01"));
        assertThat(september.to()).isEqualTo(LocalDate.parse("2026-09-30"));
        assertThat(september.contains(LocalDate.parse("2026-09-30"))).isTrue();
        assertThat(september.contains(LocalDate.parse("2026-10-01"))).isFalse();
        assertThat(AnalyticsPeriod.months(YearMonth.of(2026, 4), YearMonth.of(2026, 9)).from())
                .isEqualTo(LocalDate.parse("2026-04-01"));
        assertThatThrownBy(() -> new AnalyticsPeriod(LocalDate.parse("2026-09-02"), LocalDate.parse("2026-09-01")))
                .isInstanceOf(InvalidValueException.class);
    }
}
