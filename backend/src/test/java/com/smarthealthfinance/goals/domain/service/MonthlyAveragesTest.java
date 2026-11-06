package com.smarthealthfinance.goals.domain.service;

import com.smarthealthfinance.analytics.domain.model.FlowEntry;
import com.smarthealthfinance.analytics.domain.valueobject.AnalysisPeriod;
import com.smarthealthfinance.health.domain.model.HealthWindow;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.enums.CashFlowRole;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Médias com a janela do Health (ADR-0017 §1, ADR-0036), com paridade contra a {@link HealthWindow}. */
class MonthlyAveragesTest {

    private static final Currency BRL = Currency.getInstance("BRL");

    @ParameterizedTest(name = "hoje {0}, primeiro lançamento {1}")
    @CsvSource({
            "2026-09-22, 2026-04-01",
            "2026-09-22, 2025-01-15",
            "2026-09-01, 2026-08-31",
            "2026-09-22, 2026-09-05",
            "2027-01-10, 2026-04-01",
            "2026-09-30, 2026-04-01"
    })
    void windowMatchesTheHealthWindowForTheCurrentMonth(LocalDate today, LocalDate first) {
        AnalysisPeriod current = AnalysisPeriod.resolve(null, today);

        assertThat(MonthlyAverages.window(today, Optional.of(first)))
                .isEqualTo(HealthWindow.of(current, Optional.of(first)).months());
    }

    @Test
    void averagesOfTheSampleDataGiveTheReserveTarget() {
        List<FlowEntry> entries = new ArrayList<>();
        String[] income = {"6500.00", "6500.00", "6500.00", "6800.00", "6500.00"};
        String[] expense = {"4020.00", "4310.00", "3980.00", "4460.00", "4190.00"};
        for (int i = 0; i < income.length; i++) {
            LocalDate day = YearMonth.of(2026, 4 + i).atDay(10);
            entries.add(new FlowEntry(day, CashFlowRole.INCOME, "SALARY", null, null, Money.of(income[i], BRL), 1));
            entries.add(new FlowEntry(day, CashFlowRole.EXPENSE, "HOUSING", null, null, Money.of(expense[i], BRL), 1));
        }
        List<YearMonth> window = MonthlyAverages.window(LocalDate.of(2026, 9, 22), Optional.of(LocalDate.of(2026, 4, 1)));

        MonthlyAverages.Averages averages = MonthlyAverages.of(window, entries, BRL);

        // Média de despesas abr–ago: R$ 4.192 → reserva de 6 meses: R$ 25.152 (09-dados).
        assertThat(averages.expense()).isEqualTo(Money.of("4192.00", BRL));
        assertThat(averages.reserveTarget()).contains(Money.of("25152.00", BRL));
        assertThat(averages.income()).isEqualTo(Money.of("6560.00", BRL));
    }

    @Test
    void noWindowWithoutActivityOrClosedMonth() {
        assertThat(MonthlyAverages.window(LocalDate.of(2026, 9, 22), Optional.empty())).isEmpty();
        MonthlyAverages.Averages none = MonthlyAverages.of(List.of(), List.of(), BRL);
        assertThat(none.reserveTarget()).isEmpty();
        assertThat(none.incomeIfAny()).isEmpty();
    }
}
