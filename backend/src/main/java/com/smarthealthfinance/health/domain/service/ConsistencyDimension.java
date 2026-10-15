package com.smarthealthfinance.health.domain.service;

import com.smarthealthfinance.analytics.domain.model.FlowEntry;
import com.smarthealthfinance.analytics.domain.service.FlowLedger;
import com.smarthealthfinance.analytics.domain.service.IncomeSources;
import com.smarthealthfinance.analytics.domain.valueobject.AnalyticsPeriod;
import com.smarthealthfinance.health.domain.enums.EvidenceKind;
import com.smarthealthfinance.health.domain.enums.EvidenceSource;
import com.smarthealthfinance.health.domain.enums.HealthDimension;
import com.smarthealthfinance.health.domain.enums.InsufficientReason;
import com.smarthealthfinance.health.domain.model.DimensionResult;
import com.smarthealthfinance.health.domain.model.Evidence;
import com.smarthealthfinance.health.domain.model.HealthInput;
import com.smarthealthfinance.health.domain.model.HealthWindow;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.enums.CashFlowRole;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Consistency (ADR-0017 §2), mínimo n ≥ 3. Mês regular = receita e despesa a até ±15% das medianas de W.
 * y = % da receita de W vinda de fontes recorrentes: a fonte do ADR-0015 §6 (comerciante; sem comerciante, a
 * categoria) recebida em 3 ou mais meses da janela. Nota = 0,7 · 100 · regulares ÷ n + 0,3 · y.
 */
final class ConsistencyDimension {

    static final int MIN_MONTHS = 3;
    private static final BigDecimal TOLERANCE = new BigDecimal("0.15");

    private ConsistencyDimension() {
    }

    static DimensionResult assess(HealthInput input, HealthWindow window) {
        if (window.size() < MIN_MONTHS) {
            return DimensionResult.insufficient(HealthDimension.CONSISTENCY,
                    InsufficientReason.NEEDS_THREE_CLOSED_MONTHS);
        }
        FlowLedger ledger = input.ledger();
        List<BigDecimal> incomes = new ArrayList<>();
        List<BigDecimal> expenses = new ArrayList<>();
        for (YearMonth month : window.months()) {
            incomes.add(ledger.total(CashFlowRole.INCOME, AnalyticsPeriod.of(month)).amount());
            expenses.add(ledger.total(CashFlowRole.EXPENSE, AnalyticsPeriod.of(month)).amount());
        }
        BigDecimal medianIncome = median(incomes);
        BigDecimal medianExpense = median(expenses);
        int regular = 0;
        for (int i = 0; i < window.size(); i++) {
            if (withinTolerance(incomes.get(i), medianIncome) && withinTolerance(expenses.get(i), medianExpense)) {
                regular++;
            }
        }
        int n = window.size();

        AnalyticsPeriod range = window.range();
        Money income = ledger.total(CashFlowRole.INCOME, range);
        BigDecimal recurringShare = income.amount().signum() == 0 ? BigDecimal.ZERO.setScale(2)
                : HealthTexts.toPercent(Ratios.divide(recurringIncome(ledger, range).amount(), income.amount()))
                        .setScale(2);

        int score = Ratios.toScore(Ratios.weighted("0.7", Ratios.divide(regular, n).multiply(BigDecimal.valueOf(100)))
                .add(Ratios.weighted("0.3", recurringShare)));

        String explanation = ("Receitas e despesas ficaram dentro do seu padrão em %d de %d meses; %s da receita vem "
                + "de fontes recorrentes.").formatted(regular, n, HealthTexts.percent(recurringShare));

        List<Evidence> evidence = List.of(
                new Evidence("regularMonths", "Meses dentro do padrão (±15%)", EvidenceKind.COUNT,
                        BigDecimal.valueOf(regular), range.from(), range.to(), EvidenceSource.ANALYTICS_MONTHLY),
                new Evidence("months", "Meses fechados", EvidenceKind.COUNT, BigDecimal.valueOf(n), range.from(),
                        range.to(), EvidenceSource.ANALYTICS_MONTHLY),
                new Evidence("medianIncome", "Mediana mensal das receitas", EvidenceKind.MONEY,
                        medianIncome.setScale(2, RoundingMode.HALF_EVEN), range.from(), range.to(),
                        EvidenceSource.ANALYTICS_MONTHLY),
                new Evidence("medianExpense", "Mediana mensal das despesas", EvidenceKind.MONEY,
                        medianExpense.setScale(2, RoundingMode.HALF_EVEN), range.from(), range.to(),
                        EvidenceSource.ANALYTICS_MONTHLY),
                new Evidence("recurringIncomeShare", "Receita de fontes recorrentes", EvidenceKind.PERCENT,
                        recurringShare, range.from(), range.to(), EvidenceSource.ANALYTICS_INCOME_SOURCES));

        return DimensionResult.ok(HealthDimension.CONSISTENCY, score, explanation, false, evidence);
    }

    /** Mediana exata; com n par, a média dos dois do meio. */
    static BigDecimal median(List<BigDecimal> values) {
        List<BigDecimal> sorted = values.stream().sorted().toList();
        int middle = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(middle);
        }
        return sorted.get(middle - 1).add(sorted.get(middle)).divide(BigDecimal.valueOf(2));
    }

    private static boolean withinTolerance(BigDecimal value, BigDecimal median) {
        return value.subtract(median).abs().compareTo(median.abs().multiply(TOLERANCE)) <= 0;
    }

    private static Money recurringIncome(FlowLedger ledger, AnalyticsPeriod range) {
        List<FlowEntry> entries = ledger.entries(CashFlowRole.INCOME, range);
        Map<String, Set<YearMonth>> months = new HashMap<>();
        entries.forEach(e -> months.computeIfAbsent(IncomeSources.sourceKey(e), k -> new HashSet<>())
                .add(YearMonth.from(e.day())));
        return entries.stream()
                .filter(e -> months.get(IncomeSources.sourceKey(e)).size() >= IncomeSources.RECURRING_MONTHS)
                .map(FlowEntry::amount)
                .reduce(ledger.zero(), Money::plus);
    }
}
