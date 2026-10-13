package com.smarthealthfinance.analytics.domain.service;

import com.smarthealthfinance.analytics.domain.enums.ChangeKind;
import com.smarthealthfinance.analytics.domain.valueobject.AnalyticsPeriod;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.enums.CashFlowRole;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * "Por que meu dinheiro mudou?", parte 2 (ADR-0015 §5): decomposição determinística da variação do resultado contra
 * a base de comparação. Cada item traz o impacto no resultado: receitas e reembolsos com o sinal da própria variação,
 * categorias de despesa com o sinal invertido (gastar mais reduz o resultado).
 * <p>
 * Fecha exatamente: {@code Σ impact = netDelta}. Com a média como base, cada componente é a média do componente
 * (2 casas, HALF_EVEN) e o resultado-base é a soma desses componentes arredondados — por isso pode diferir em
 * centavos da média do resultado calculada à parte, mas nunca da soma dos itens.
 *
 * @param items maior impacto absoluto primeiro; itens sem variação ficam de fora
 */
public record ChangeBreakdown(Money currentNet, Money baseNet, Money netDelta, List<Item> items) {

    /** Comerciantes que mais contribuíram para a variação de cada categoria. */
    public static final int TOP_MERCHANTS = 3;

    /**
     * @param categoryCode só em {@code EXPENSE_CATEGORY}; nulo = sem categoria
     * @param delta        atual − base
     * @param impact       efeito no resultado
     */
    public record Item(ChangeKind kind, String categoryCode, Money current, Money base, Money delta, Money impact,
                       List<MerchantChange> merchants) {

        public Item {
            Objects.requireNonNull(kind, "kind");
            merchants = List.copyOf(merchants);
        }
    }

    /** @param merchantKey nulo = comerciante não identificado */
    public record MerchantChange(String merchantKey, Money current, Money base, Money delta) {
    }

    public ChangeBreakdown {
        items = List.copyOf(items);
    }

    /** @param baseWindows uma janela (mês anterior) ou várias (média) */
    public static ChangeBreakdown of(FlowLedger ledger, AnalyticsPeriod current, List<AnalyticsPeriod> baseWindows) {
        if (baseWindows.isEmpty()) {
            throw new IllegalArgumentException("Sem base de comparação");
        }
        List<Item> items = new ArrayList<>();

        Money income = ledger.total(CashFlowRole.INCOME, current);
        Money incomeBase = ledger.average(baseWindows, w -> ledger.total(CashFlowRole.INCOME, w));
        Money refunds = ledger.total(CashFlowRole.REFUND, current);
        Money refundsBase = ledger.average(baseWindows, w -> ledger.total(CashFlowRole.REFUND, w));
        items.add(item(ChangeKind.INCOME, null, income, incomeBase, false, List.of()));
        items.add(item(ChangeKind.REFUNDS, null, refunds, refundsBase, false, List.of()));

        Money currentNet = income.plus(refunds);
        Money baseNet = incomeBase.plus(refundsBase);

        Map<String, Money> currentCategories = ledger.byCategory(CashFlowRole.EXPENSE, current);
        Set<String> categories = new LinkedHashSet<>(currentCategories.keySet());
        baseWindows.forEach(w -> categories.addAll(ledger.byCategory(CashFlowRole.EXPENSE, w).keySet()));

        for (String category : categories) {
            Money spent = currentCategories.getOrDefault(category, ledger.zero());
            Money spentBase = ledger.average(baseWindows,
                    w -> ledger.byCategory(CashFlowRole.EXPENSE, w).getOrDefault(category, ledger.zero()));
            currentNet = currentNet.minus(spent);
            baseNet = baseNet.minus(spentBase);
            items.add(item(ChangeKind.EXPENSE_CATEGORY, category, spent, spentBase, true,
                    merchants(ledger, category, current, baseWindows)));
        }

        List<Item> changed = items.stream()
                .filter(item -> item.delta().amount().signum() != 0)
                .sorted(Comparator.comparing((Item item) -> item.impact().amount().abs()).reversed()
                        .thenComparing(item -> item.kind().ordinal())
                        .thenComparing(item -> item.categoryCode() == null ? "￿" : item.categoryCode()))
                .toList();
        return new ChangeBreakdown(currentNet, baseNet, currentNet.minus(baseNet), changed);
    }

    private static Item item(ChangeKind kind, String category, Money current, Money base, boolean expense,
                             List<MerchantChange> merchants) {
        Money delta = current.minus(base);
        return new Item(kind, category, current, base, delta, expense ? delta.negate() : delta, merchants);
    }

    private static List<MerchantChange> merchants(FlowLedger ledger, String category, AnalyticsPeriod current,
                                                  List<AnalyticsPeriod> baseWindows) {
        Map<String, Money> now = ledger.byMerchantIn(CashFlowRole.EXPENSE, category, current);
        Set<String> keys = new LinkedHashSet<>(now.keySet());
        baseWindows.forEach(w -> keys.addAll(ledger.byMerchantIn(CashFlowRole.EXPENSE, category, w).keySet()));

        return keys.stream()
                .map(key -> {
                    Money amount = now.getOrDefault(key, ledger.zero());
                    Money base = ledger.average(baseWindows,
                            w -> ledger.byMerchantIn(CashFlowRole.EXPENSE, category, w).getOrDefault(key, ledger.zero()));
                    return new MerchantChange(key, amount, base, amount.minus(base));
                })
                .filter(change -> change.delta().amount().signum() != 0)
                .sorted(Comparator.comparing((MerchantChange change) -> change.delta().amount().abs()).reversed()
                        .thenComparing(change -> change.merchantKey() == null ? "￿" : change.merchantKey()))
                .limit(TOP_MERCHANTS)
                .toList();
    }
}
