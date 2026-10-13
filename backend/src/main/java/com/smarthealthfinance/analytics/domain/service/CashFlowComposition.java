package com.smarthealthfinance.analytics.domain.service;

import com.smarthealthfinance.analytics.domain.enums.OutflowGroup;
import com.smarthealthfinance.analytics.domain.valueobject.AnalyticsPeriod;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.enums.CashFlowRole;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * "Por que meu dinheiro mudou?", parte 1 (ADR-0015 §5): de onde veio e para onde foi o dinheiro do período.
 * Entradas (receitas + reembolsos), saídas por categoria (as {@code top} maiores e o resto agrupado) e o resultado,
 * que fecha exatamente: {@code inflows − Σ outflows = net}.
 *
 * @param outflows maior primeiro; o grupo "demais categorias" (se houver) é sempre o último
 */
public record CashFlowComposition(Money income, Money refunds, Money inflows, List<Outflow> outflows, Money net) {

    /** Quantas categorias aparecem sozinhas antes do grupo "demais categorias" (layout D-Analysis). */
    public static final int TOP_CATEGORIES = 4;

    /**
     * @param categoryCode só em {@code CATEGORY}
     * @param categories   quantas categorias a linha soma (1, ou mais em {@code OTHERS})
     */
    public record Outflow(OutflowGroup group, String categoryCode, Money amount, int categories) {

        public Outflow {
            Objects.requireNonNull(group, "group");
            Objects.requireNonNull(amount, "amount");
        }
    }

    public CashFlowComposition {
        outflows = List.copyOf(outflows);
    }

    public static CashFlowComposition of(FlowLedger ledger, AnalyticsPeriod window) {
        Money income = ledger.total(CashFlowRole.INCOME, window);
        Money refunds = ledger.total(CashFlowRole.REFUND, window);
        Map<String, Money> byCategory = ledger.byCategory(CashFlowRole.EXPENSE, window);

        List<Outflow> outflows = new ArrayList<>();
        Money others = ledger.zero();
        int grouped = 0;
        int index = 0;
        for (Map.Entry<String, Money> category : byCategory.entrySet()) {
            if (index++ < TOP_CATEGORIES) {
                outflows.add(category.getKey() == null
                        ? new Outflow(OutflowGroup.UNCATEGORIZED, null, category.getValue(), 1)
                        : new Outflow(OutflowGroup.CATEGORY, category.getKey(), category.getValue(), 1));
            }
            else {
                others = others.plus(category.getValue());
                grouped++;
            }
        }
        if (grouped > 0) {
            outflows.add(new Outflow(OutflowGroup.OTHERS, null, others, grouped));
        }

        Money inflows = income.plus(refunds);
        Money spent = outflows.stream().map(Outflow::amount).reduce(ledger.zero(), Money::plus);
        return new CashFlowComposition(income, refunds, inflows, outflows, inflows.minus(spent));
    }
}
