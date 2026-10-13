package com.smarthealthfinance.analytics.application.service;

import com.smarthealthfinance.analytics.application.dto.AnalysisViews.CategoryLine;
import com.smarthealthfinance.analytics.application.dto.AnalysisViews.MonthBar;
import com.smarthealthfinance.analytics.domain.model.Variation;
import com.smarthealthfinance.analytics.domain.service.FlowLedger;
import com.smarthealthfinance.analytics.domain.valueobject.AnalyticsPeriod;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.enums.CashFlowRole;

import java.util.List;
import java.util.Map;

/** Cálculos compartilhados pelas consultas da Análise, todos sobre o {@link FlowLedger} do contexto. */
public final class AnalysisCalculations {

    private AnalysisCalculations() {
    }

    /** Barras dos {@code count} meses até o período (inclusive); meses sem movimento valem zero. */
    public static List<MonthBar> monthBars(AnalysisContext context, int count) {
        FlowLedger ledger = context.ledger();
        return context.period().lastMonths(count).stream()
                .map(month -> {
                    AnalyticsPeriod window = context.period().historyRange(month);
                    boolean partial = month.equals(context.period().month()) && context.period().partial();
                    return new MonthBar(month, partial, ledger.total(CashFlowRole.INCOME, window),
                            ledger.total(CashFlowRole.EXPENSE, window), ledger.total(CashFlowRole.REFUND, window),
                            ledger.net(window));
                })
                .toList();
    }

    /**
     * Linhas por categoria do papel ({@code EXPENSE} ou {@code INCOME}) no período, maior primeiro; categorias que só
     * existem na base não aparecem (o "Por que meu dinheiro mudou?" mostra o que sumiu).
     */
    public static List<CategoryLine> categoryLines(AnalysisContext context, CashFlowRole role) {
        FlowLedger ledger = context.ledger();
        AnalyticsPeriod current = context.current();
        Map<String, Money> amounts = ledger.byCategory(role, current);
        Map<String, Integer> counts = ledger.countByCategory(role, current);
        Map<String, Money> refunds = role == CashFlowRole.EXPENSE ? ledger.byCategory(CashFlowRole.REFUND, current)
                : Map.of();
        Money total = ledger.total(role, current);

        return amounts.entrySet().stream()
                .map(entry -> {
                    String category = entry.getKey();
                    Money amount = entry.getValue();
                    Money previous = context.previous(w -> ledger.byCategory(role, w).getOrDefault(category,
                            ledger.zero()));
                    Money average = context.average(w -> ledger.byCategory(role, w).getOrDefault(category,
                            ledger.zero()));
                    return new CategoryLine(category, amount, FlowLedger.share(amount, total),
                            counts.getOrDefault(category, 0), refunds.getOrDefault(category, ledger.zero()),
                            Variation.of(amount, previous), Variation.of(amount, average));
                })
                .toList();
    }
}
