package com.smarthealthfinance.health.domain.service;

import com.smarthealthfinance.analytics.domain.enums.ComparisonBase;
import com.smarthealthfinance.analytics.domain.model.ComparisonPlan;
import com.smarthealthfinance.analytics.domain.service.FlowLedger;
import com.smarthealthfinance.analytics.domain.valueobject.AnalysisPeriod;
import com.smarthealthfinance.analytics.domain.valueobject.AnalyticsPeriod;
import com.smarthealthfinance.health.domain.enums.EvidenceKind;
import com.smarthealthfinance.health.domain.enums.EvidenceSource;
import com.smarthealthfinance.health.domain.enums.HealthDimension;
import com.smarthealthfinance.health.domain.enums.InsufficientReason;
import com.smarthealthfinance.health.domain.model.DimensionResult;
import com.smarthealthfinance.health.domain.model.Evidence;
import com.smarthealthfinance.health.domain.model.HealthInput;
import com.smarthealthfinance.health.domain.valueobject.ScoreCurve;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.enums.CashFlowRole;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Spending (ADR-0017 §2): o mês de referência contra a média dos 5 meses anteriores, no mesmo recorte de dias, com
 * despesa bruta — as mesmas bases da Análise ({@link ComparisonPlan}, ADR-0015 §2/§4). Nota = 0,5 · S(Δ) + 0,5 · S(E).
 * Mínimo: 1 mês na média, e média diferente de zero.
 */
final class SpendingDimension {

    static final int AVERAGE_MONTHS = ComparisonPlan.DEFAULT_AVERAGE_MONTHS;
    static final ScoreCurve VARIATION_CURVE = ScoreCurve.of("0", "100", "0.10", "75", "0.25", "40", "0.50", "0");
    static final ScoreCurve EXCESS_CURVE = ScoreCurve.of("0", "100", "0.10", "70", "0.25", "30", "0.40", "0");

    /** Contribuição de uma categoria para E a partir da qual há ponto de atenção (5 pontos percentuais). */
    private static final BigDecimal ATTENTION_CONTRIBUTION = new BigDecimal("0.05");

    /**
     * @param topCategory   categoria com maior excesso (nula = sem categoria); só com {@code hasTopCategory}
     * @param topExcess     excesso da categoria sobre a média dela
     * @param topPercent    excesso em % da média da categoria; nulo se a categoria não estava na média
     */
    record Assessment(DimensionResult result, boolean hasTopCategory, String topCategory, Money topExcess,
                      BigDecimal topPercent, String cut) {}

    private SpendingDimension() {
    }

    static Assessment assess(HealthInput input) {
        AnalysisPeriod period = input.period();
        ComparisonPlan plan = ComparisonPlan.of(period, ComparisonBase.AVERAGE, AVERAGE_MONTHS,
                input.firstActivity());
        if (!plan.hasAverage()) {
            return none(InsufficientReason.NO_AVERAGE);
        }
        FlowLedger ledger = input.ledger();
        AnalyticsPeriod current = period.range();
        List<AnalyticsPeriod> windows = plan.averageWindows();
        Money expense = ledger.total(CashFlowRole.EXPENSE, current);
        Money average = ledger.average(windows, w -> ledger.total(CashFlowRole.EXPENSE, w));
        if (average.amount().signum() == 0) {
            return none(InsufficientReason.ZERO_AVERAGE);
        }

        BigDecimal variation = Ratios.divide(expense.minus(average).amount(), average.amount());

        BigDecimal totalExcess = BigDecimal.ZERO;
        boolean attention = false;
        String topCategory = null;
        Money topExcess = null;
        Money topAverage = null;
        boolean hasTop = false;
        // byCategory já vem do maior para o menor valor, com empate determinístico.
        for (Map.Entry<String, Money> category : ledger.byCategory(CashFlowRole.EXPENSE, current).entrySet()) {
            String code = category.getKey();
            Money categoryAverage = ledger.average(windows,
                    w -> ledger.byCategory(CashFlowRole.EXPENSE, w).getOrDefault(code, ledger.zero()));
            Money excess = category.getValue().minus(categoryAverage);
            if (excess.amount().signum() <= 0) {
                continue;
            }
            totalExcess = totalExcess.add(excess.amount());
            if (Ratios.divide(excess.amount(), average.amount()).compareTo(ATTENTION_CONTRIBUTION) >= 0) {
                attention = true;
            }
            if (!hasTop || excess.amount().compareTo(topExcess.amount()) > 0) {
                hasTop = true;
                topCategory = code;
                topExcess = excess;
                topAverage = categoryAverage;
            }
        }
        BigDecimal excessRatio = Ratios.divide(totalExcess, average.amount());

        int score = Ratios.toScore(Ratios.weighted("0.5", VARIATION_CURVE.score(variation))
                .add(Ratios.weighted("0.5", EXCESS_CURVE.score(excessRatio))));

        String cut = period.partial()
                ? "1 a %d de %s".formatted(period.daysElapsed(), HealthTexts.month(period.month()))
                : HealthTexts.month(period.month());
        BigDecimal topPercent = !hasTop || topAverage.amount().signum() == 0 ? null
                : HealthTexts.toPercent(Ratios.divide(topExcess.amount(), topAverage.amount()));
        StringBuilder explanation = new StringBuilder("Gasto %s vs sua média (%s).".formatted(
                HealthTexts.signedPercent(HealthTexts.toPercent(variation)), cut));
        if (hasTop) {
            String name = categoryName(input, topCategory);
            explanation.append(topPercent == null
                    ? " %s não aparecia na sua média (+%s).".formatted(name, HealthTexts.money(topExcess))
                    : " %s está %s acima da média (+%s).".formatted(name, HealthTexts.percent(topPercent),
                            HealthTexts.money(topExcess)));
        }

        AnalyticsPeriod averageRange = new AnalyticsPeriod(windows.getFirst().from(), windows.getLast().to());
        List<Evidence> evidence = new ArrayList<>();
        evidence.add(new Evidence("expense", "Despesas no período", EvidenceKind.MONEY, expense.amount(),
                current.from(), current.to(), EvidenceSource.ANALYTICS_MONTHLY));
        evidence.add(new Evidence("averageExpense", "Média das despesas no mesmo recorte", EvidenceKind.MONEY,
                average.amount(), averageRange.from(), averageRange.to(), EvidenceSource.ANALYTICS_MONTHLY));
        evidence.add(new Evidence("averageMonths", "Meses na média", EvidenceKind.COUNT,
                BigDecimal.valueOf(plan.monthsUsed()), averageRange.from(), averageRange.to(),
                EvidenceSource.ANALYTICS_MONTHLY));
        evidence.add(new Evidence("variation", "Variação contra a média", EvidenceKind.PERCENT,
                HealthTexts.toPercent(variation).setScale(2), current.from(), current.to(),
                EvidenceSource.ANALYTICS_MONTHLY));
        evidence.add(new Evidence("categoryExcess", "Excesso por categoria sobre a média", EvidenceKind.PERCENT,
                HealthTexts.toPercent(excessRatio).setScale(2), current.from(), current.to(),
                EvidenceSource.ANALYTICS_CATEGORIES));

        return new Assessment(DimensionResult.ok(HealthDimension.SPENDING, score, explanation.toString(), attention,
                evidence), hasTop && attention, topCategory, topExcess, topPercent, cut);
    }

    static String categoryName(HealthInput input, String code) {
        if (code == null) {
            return "Sem categoria";
        }
        return input.categoryNames().getOrDefault(code, code);
    }

    private static Assessment none(InsufficientReason reason) {
        return new Assessment(DimensionResult.insufficient(HealthDimension.SPENDING, reason), false, null, null, null,
                null);
    }
}
