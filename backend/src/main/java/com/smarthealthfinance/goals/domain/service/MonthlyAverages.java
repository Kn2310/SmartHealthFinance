package com.smarthealthfinance.goals.domain.service;

import com.smarthealthfinance.analytics.domain.model.FlowEntry;
import com.smarthealthfinance.analytics.domain.service.FlowLedger;
import com.smarthealthfinance.analytics.domain.valueobject.AnalyticsPeriod;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.enums.CashFlowRole;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Médias mensais de despesas e receitas com a janela W do Health (ADR-0017 §1, ADR-0036): até os
 * {@value #MAX_MONTHS} últimos meses fechados até hoje, a partir do mês do primeiro lançamento; zeros
 * contam; total ÷ n com 2 casas {@code HALF_EVEN}, pelo mesmo {@link FlowLedger}. Usada no alvo sugerido da reserva
 * (6 × despesa média) e no peso na renda. A paridade com o Health é verificada em teste.
 */
public final class MonthlyAverages {

    public static final int MAX_MONTHS = 6;

    /** Referência da reserva do Health (ADR-0017 Reserve): 6 meses de despesas. */
    public static final int RESERVE_REFERENCE_MONTHS = 6;

    private MonthlyAverages() {
    }

    /** @param months do mais antigo ao mais recente; vazio sem mês fechado com atividade */
    public record Averages(List<YearMonth> months, Money expense, Money income) {

        public Averages {
            months = List.copyOf(months);
        }

        /** 6 × a despesa média; vazio sem despesa. */
        public Optional<Money> reserveTarget() {
            if (expense == null || expense.amount().signum() <= 0) {
                return Optional.empty();
            }
            return Optional.of(new Money(expense.amount().multiply(java.math.BigDecimal.valueOf(
                    RESERVE_REFERENCE_MONTHS)), expense.currency()));
        }

        public Optional<Money> incomeIfAny() {
            return income == null || income.amount().signum() <= 0 ? Optional.empty() : Optional.of(income);
        }
    }

    /**
     * A janela W da data de hoje, como a do Health para o mês corrente: termina no mês anterior, ou no próprio mês
     * quando hoje é o último dia dele (o mês já não é parcial).
     */
    public static List<YearMonth> window(LocalDate today, Optional<LocalDate> firstActivity) {
        Objects.requireNonNull(today, "today");
        if (firstActivity.isEmpty()) {
            return List.of();
        }
        YearMonth first = YearMonth.from(firstActivity.get());
        YearMonth current = YearMonth.from(today);
        YearMonth last = today.equals(current.atEndOfMonth()) ? current : current.minusMonths(1);
        List<YearMonth> months = new ArrayList<>();
        for (int i = MAX_MONTHS - 1; i >= 0; i--) {
            YearMonth month = last.minusMonths(i);
            if (!month.isBefore(first)) {
                months.add(month);
            }
        }
        return months;
    }

    /** O período a ler do Analytics para a janela; vazio sem janela. */
    public static Optional<AnalyticsPeriod> range(List<YearMonth> window) {
        return window.isEmpty() ? Optional.empty()
                : Optional.of(AnalyticsPeriod.months(window.getFirst(), window.getLast()));
    }

    public static Averages of(List<YearMonth> window, List<FlowEntry> entries, Currency currency) {
        if (window.isEmpty()) {
            return new Averages(window, null, null);
        }
        FlowLedger ledger = new FlowLedger(entries, currency);
        AnalyticsPeriod range = AnalyticsPeriod.months(window.getFirst(), window.getLast());
        return new Averages(window, ledger.divide(ledger.total(CashFlowRole.EXPENSE, range), window.size()),
                ledger.divide(ledger.total(CashFlowRole.INCOME, range), window.size()));
    }
}
