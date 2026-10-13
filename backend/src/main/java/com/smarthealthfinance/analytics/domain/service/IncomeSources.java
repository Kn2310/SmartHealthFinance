package com.smarthealthfinance.analytics.domain.service;

import com.smarthealthfinance.analytics.domain.enums.Recurrence;
import com.smarthealthfinance.analytics.domain.model.FlowEntry;
import com.smarthealthfinance.analytics.domain.valueobject.AnalysisPeriod;
import com.smarthealthfinance.analytics.domain.valueobject.AnalyticsPeriod;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.enums.CashFlowRole;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Fontes de receita do período (ADR-0015 §6). Fonte = comerciante da receita; sem comerciante, a categoria.
 * <ul>
 *   <li>{@code RECURRING}: recebida em {@value #RECURRING_MONTHS} ou mais dos {@value #HISTORY_MONTHS} meses até o
 *   período (inclusive); {@code typicalDay} = dia do mês mais frequente nesses meses (empate: o menor).</li>
 *   <li>{@code OCCASIONAL}: as demais; sem dia típico.</li>
 * </ul>
 * Nenhuma previsão: a "próxima receita prevista" é do M9 Forecast.
 */
public final class IncomeSources {

    public static final int HISTORY_MONTHS = 6;
    public static final int RECURRING_MONTHS = 3;

    /**
     * @param merchantKey  nulo quando a fonte é a categoria
     * @param categoryCode categoria de maior valor da fonte no período (nula = sem categoria)
     * @param share        % das receitas do período, 1 casa
     */
    public record Source(String merchantKey, String merchantName, String categoryCode, Money amount,
                         BigDecimal share, int transactionCount, Recurrence recurrence, Integer typicalDay,
                         LocalDate lastReceivedOn) {

        public Source {
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(recurrence, "recurrence");
        }
    }

    private IncomeSources() {
    }

    public static List<Source> of(FlowLedger ledger, AnalysisPeriod period) {
        AnalyticsPeriod current = period.range();
        Money total = ledger.total(CashFlowRole.INCOME, current);
        Map<String, String> names = ledger.merchantNames();

        Map<String, List<FlowEntry>> now = bySource(ledger.entries(CashFlowRole.INCOME, current));
        Map<String, List<FlowEntry>> history = new LinkedHashMap<>();
        for (YearMonth month : period.lastMonths(HISTORY_MONTHS)) {
            bySource(ledger.entries(CashFlowRole.INCOME, period.historyRange(month)))
                    .forEach((key, items) -> history.computeIfAbsent(key, k -> new java.util.ArrayList<>()).addAll(items));
        }

        return now.entrySet().stream()
                .map(source -> {
                    List<FlowEntry> items = source.getValue();
                    FlowEntry sample = items.getFirst();
                    Money amount = items.stream().map(FlowEntry::amount).reduce(ledger.zero(), Money::plus);
                    int count = items.stream().mapToInt(FlowEntry::transactionCount).sum();
                    List<FlowEntry> past = history.getOrDefault(source.getKey(), List.of());
                    boolean recurring = monthsWith(past) >= RECURRING_MONTHS;
                    return new Source(sample.merchantKey(),
                            sample.merchantKey() == null ? null : names.get(sample.merchantKey()),
                            mainCategory(items), amount, FlowLedger.share(amount, total), count,
                            recurring ? Recurrence.RECURRING : Recurrence.OCCASIONAL,
                            recurring ? typicalDay(past) : null,
                            items.stream().map(FlowEntry::day).max(Comparator.naturalOrder()).orElseThrow());
                })
                .sorted(Comparator.comparing((Source s) -> s.amount().amount()).reversed()
                        .thenComparing(s -> s.merchantKey() == null ? "￿" : s.merchantKey()))
                .toList();
    }

    private static Map<String, List<FlowEntry>> bySource(List<FlowEntry> entries) {
        Map<String, List<FlowEntry>> grouped = new LinkedHashMap<>();
        entries.forEach(e -> grouped.computeIfAbsent(key(e), k -> new java.util.ArrayList<>()).add(e));
        return grouped;
    }

    private static String key(FlowEntry entry) {
        return entry.merchantKey() != null ? "merchant:" + entry.merchantKey()
                : "category:" + Objects.requireNonNullElse(entry.categoryCode(), "");
    }

    private static long monthsWith(List<FlowEntry> entries) {
        Set<YearMonth> months = new HashSet<>();
        entries.forEach(e -> months.add(YearMonth.from(e.day())));
        return months.size();
    }

    private static Integer typicalDay(List<FlowEntry> entries) {
        Map<Integer, Integer> frequency = new TreeMap<>();
        entries.forEach(e -> frequency.merge(e.day().getDayOfMonth(), e.transactionCount(), Integer::sum));
        // TreeMap em ordem de dia: no empate, o max mantém o primeiro (o menor dia).
        return frequency.entrySet().stream()
                .reduce((best, next) -> next.getValue() > best.getValue() ? next : best)
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private static String mainCategory(List<FlowEntry> items) {
        Map<String, BigDecimal> byCategory = new LinkedHashMap<>();
        items.forEach(e -> byCategory.merge(e.categoryCode(), e.amount().amount(), BigDecimal::add));
        return byCategory.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed()
                        .thenComparing(e -> e.getKey() == null ? "￿" : e.getKey()))
                .findFirst()
                // Chave nula = "Sem categoria": Optional.map devolve vazio, e o resultado é nulo de propósito.
                .map(Map.Entry::getKey)
                .orElse(null);
    }
}
