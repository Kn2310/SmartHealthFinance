package com.smarthealthfinance.analytics.domain.service;

import com.smarthealthfinance.analytics.domain.model.FlowEntry;
import com.smarthealthfinance.analytics.domain.valueobject.AnalyticsPeriod;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.enums.CashFlowRole;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Agregações exatas sobre as linhas do fluxo de caixa (ADR-0015 §1). Tudo em {@link Money}/BigDecimal; a única
 * divisão é a média, com 2 casas e HALF_EVEN ({@link #average}), e o percentual de participação ({@link #share}).
 * Chaves nulas (sem categoria, sem comerciante) são grupos legítimos.
 */
public final class FlowLedger {

    private final List<FlowEntry> entries;
    private final Currency currency;

    public FlowLedger(List<FlowEntry> entries, Currency currency) {
        this.entries = List.copyOf(entries);
        this.currency = Objects.requireNonNull(currency, "currency");
    }

    public Currency currency() {
        return currency;
    }

    public Money zero() {
        return Money.zero(currency);
    }

    public boolean isEmpty(AnalyticsPeriod window) {
        return in(window).findAny().isEmpty();
    }

    public Money total(CashFlowRole role, AnalyticsPeriod window) {
        return sum(of(role, window));
    }

    public int count(CashFlowRole role, AnalyticsPeriod window) {
        return of(role, window).mapToInt(FlowEntry::transactionCount).sum();
    }

    /** Resultado do intervalo: receitas + reembolsos − despesas (ADR-0006 §9). */
    public Money net(AnalyticsPeriod window) {
        return total(CashFlowRole.INCOME, window).plus(total(CashFlowRole.REFUND, window))
                .minus(total(CashFlowRole.EXPENSE, window));
    }

    /** Valor por categoria (chave nula = sem categoria), maior primeiro. */
    public Map<String, Money> byCategory(CashFlowRole role, AnalyticsPeriod window) {
        return sortedByAmount(group(of(role, window), FlowEntry::categoryCode));
    }

    public Map<String, Integer> countByCategory(CashFlowRole role, AnalyticsPeriod window) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        of(role, window).forEach(e -> counts.merge(e.categoryCode(), e.transactionCount(), Integer::sum));
        return counts;
    }

    /** Valor por comerciante (chave nula = não identificado), maior primeiro. */
    public Map<String, Money> byMerchant(CashFlowRole role, AnalyticsPeriod window) {
        return sortedByAmount(group(of(role, window), FlowEntry::merchantKey));
    }

    /** Valor por comerciante dentro de uma categoria (chave nula da categoria = sem categoria). */
    public Map<String, Money> byMerchantIn(CashFlowRole role, String categoryCode, AnalyticsPeriod window) {
        return sortedByAmount(group(of(role, window).filter(e -> Objects.equals(e.categoryCode(), categoryCode)),
                FlowEntry::merchantKey));
    }

    public Map<String, Integer> countByMerchant(CashFlowRole role, AnalyticsPeriod window) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        of(role, window).forEach(e -> counts.merge(e.merchantKey(), e.transactionCount(), Integer::sum));
        return counts;
    }

    /** Nome de exibição de cada comerciante (o menor, em ordem, quando há variações de grafia). */
    public Map<String, String> merchantNames() {
        Map<String, String> names = new TreeMap<>();
        entries.stream().filter(e -> e.merchantKey() != null)
                .forEach(e -> names.merge(e.merchantKey(), e.merchantName(),
                        (a, b) -> a.compareTo(b) <= 0 ? a : b));
        return names;
    }

    /** Valor por dia do intervalo (dias sem movimento não aparecem), em ordem. */
    public Map<LocalDate, Money> byDay(CashFlowRole role, AnalyticsPeriod window) {
        return new TreeMap<>(group(of(role, window), FlowEntry::day));
    }

    public List<FlowEntry> entries(CashFlowRole role, AnalyticsPeriod window) {
        return of(role, window).toList();
    }

    /** Média mensal de um valor sobre as janelas (2 casas, HALF_EVEN); nula se não há janelas. */
    public Money average(List<AnalyticsPeriod> windows, Function<AnalyticsPeriod, Money> value) {
        if (windows.isEmpty()) {
            return null;
        }
        Money sum = windows.stream().map(value).reduce(zero(), Money::plus);
        return divide(sum, windows.size());
    }

    public Money divide(Money amount, int divisor) {
        if (divisor <= 0) {
            throw new IllegalArgumentException("divisor deve ser positivo");
        }
        return new Money(amount.amount().divide(BigDecimal.valueOf(divisor), 2, RoundingMode.HALF_EVEN), currency);
    }

    /** Participação de {@code part} em {@code whole}, em %, 1 casa, HALF_EVEN; nula se o todo é zero. */
    public static BigDecimal share(Money part, Money whole) {
        if (whole.amount().signum() == 0) {
            return null;
        }
        return part.amount().multiply(BigDecimal.valueOf(100)).divide(whole.amount(), 1, RoundingMode.HALF_EVEN);
    }

    private Stream<FlowEntry> in(AnalyticsPeriod window) {
        return entries.stream().filter(e -> window.contains(e.day()));
    }

    private Stream<FlowEntry> of(CashFlowRole role, AnalyticsPeriod window) {
        return in(window).filter(e -> e.role() == role);
    }

    private Money sum(Stream<FlowEntry> items) {
        return items.map(FlowEntry::amount).reduce(zero(), Money::plus);
    }

    private <K> Map<K, Money> group(Stream<FlowEntry> items, Function<FlowEntry, K> key) {
        Map<K, Money> grouped = new LinkedHashMap<>();
        items.forEach(e -> grouped.merge(key.apply(e), e.amount(), Money::plus));
        return grouped;
    }

    /** Maior valor primeiro; empate pela chave (nula por último), para a ordem ser determinística. */
    private static <K> Map<K, Money> sortedByAmount(Map<K, Money> values) {
        Map<K, Money> sorted = new LinkedHashMap<>();
        values.entrySet().stream()
                .sorted((a, b) -> {
                    int byAmount = b.getValue().amount().compareTo(a.getValue().amount());
                    if (byAmount != 0) {
                        return byAmount;
                    }
                    if (a.getKey() == null) {
                        return b.getKey() == null ? 0 : 1;
                    }
                    return b.getKey() == null ? -1 : String.valueOf(a.getKey()).compareTo(String.valueOf(b.getKey()));
                })
                .forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return sorted;
    }
}
