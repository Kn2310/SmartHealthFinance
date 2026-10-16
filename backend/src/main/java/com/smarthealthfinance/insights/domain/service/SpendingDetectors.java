package com.smarthealthfinance.insights.domain.service;

import com.smarthealthfinance.analytics.domain.enums.ChangeKind;
import com.smarthealthfinance.analytics.domain.model.FlowEntry;
import com.smarthealthfinance.analytics.domain.service.ChangeBreakdown;
import com.smarthealthfinance.analytics.domain.service.FlowLedger;
import com.smarthealthfinance.analytics.domain.valueobject.AnalyticsPeriod;
import com.smarthealthfinance.health.domain.enums.Confidence;
import com.smarthealthfinance.health.domain.enums.HealthDimension;
import com.smarthealthfinance.insights.domain.enums.ActionKind;
import com.smarthealthfinance.insights.domain.enums.ContributorKind;
import com.smarthealthfinance.insights.domain.enums.FigureKind;
import com.smarthealthfinance.insights.domain.enums.InsightDetector;
import com.smarthealthfinance.insights.domain.enums.InsightSource;
import com.smarthealthfinance.insights.domain.model.Contributor;
import com.smarthealthfinance.insights.domain.model.InsightAction;
import com.smarthealthfinance.insights.domain.model.InsightCandidate;
import com.smarthealthfinance.insights.domain.model.InsightContent;
import com.smarthealthfinance.insights.domain.model.InsightEvidence;
import com.smarthealthfinance.insights.domain.model.InsightRefs;
import com.smarthealthfinance.insights.domain.model.KeyFigure;
import com.smarthealthfinance.insights.domain.model.Recommendation;
import com.smarthealthfinance.insights.domain.model.SourceRef;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.enums.CashFlowRole;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Detectores de gastos sobre a projeção de Analytics (ADR-0021 §2): D1 {@code CATEGORY_SPIKE}, D2
 * {@code SPENDING_ABOVE_PATTERN} e D10 {@code UNCATEGORIZED_SHARE}. Comparações sempre no mesmo recorte de dias
 * (ADR-0015 §2), pelas mesmas funções da Análise.
 */
final class SpendingDetectors {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int TOP_CONTRIBUTORS = 5;

    private SpendingDetectors() {
    }

    /** D1: até 3 categorias com alta relevante contra a média. */
    static List<InsightCandidate> categorySpikes(DetectionContext ctx) {
        if (ctx.monthsUsed() < InsightRules.MIN_AVERAGE_MONTHS) {
            return List.of();
        }
        FlowLedger ledger = ctx.ledger();
        AnalyticsPeriod current = ctx.period().range();
        List<AnalyticsPeriod> base = ctx.plan().averageWindows();

        record Spike(String category, Money spent, Money average, Money delta) {}
        List<Spike> spikes = new ArrayList<>();
        for (Map.Entry<String, Money> entry : ledger.byCategory(CashFlowRole.EXPENSE, current).entrySet()) {
            String category = entry.getKey();
            if (category == null) {
                continue;
            }
            Money average = ledger.average(base,
                    w -> ledger.byCategory(CashFlowRole.EXPENSE, w).getOrDefault(category, ledger.zero()));
            Money delta = entry.getValue().minus(average);
            if (average.amount().signum() > 0 && delta.amount().compareTo(InsightRules.CATEGORY_MIN_DELTA) >= 0
                    && atLeastPercent(delta, average, InsightRules.CATEGORY_MIN_PERCENT)
                    && atLeastShareOfReference(ctx, delta, InsightRules.CATEGORY_MIN_SHARE_OF_REFERENCE)) {
                spikes.add(new Spike(category, entry.getValue(), average, delta));
            }
        }
        return spikes.stream()
                .sorted(Comparator.comparing((Spike s) -> s.delta().amount()).reversed()
                        .thenComparing(Spike::category))
                .limit(InsightRules.CATEGORY_MAX)
                .map(s -> categorySpike(ctx, s.category(), s.spent(), s.average(), s.delta()))
                .toList();
    }

    private static InsightCandidate categorySpike(DetectionContext ctx, String category, Money spent, Money average,
                                                  Money delta) {
        FlowLedger ledger = ctx.ledger();
        AnalyticsPeriod current = ctx.period().range();
        String name = ctx.categoryName(category);
        String lower = InsightTexts.lowerFirst(name);
        String percent = InsightTexts.percent(delta.amount(), average.amount());

        List<FlowEntry> entries = ledger.entries(CashFlowRole.EXPENSE, current).stream()
                .filter(e -> category.equals(e.categoryCode())).toList();
        int purchases = entries.stream().mapToInt(FlowEntry::transactionCount).sum();
        List<Contributor> merchants = merchantContributors(ctx, entries, spent);
        Contributor top = merchants.stream().filter(c -> c.key() != null).findFirst()
                .filter(c -> atLeastPercent(new Money(c.amount(), spent.currency()), spent,
                        InsightRules.TOP_MERCHANT_SHARE))
                .orElse(null);

        String title = "Seus gastos com %s aumentaram %s%% %s, cerca de %s a mais."
                .formatted(lower, percent, ctx.inPeriod(), InsightTexts.approx(delta));
        String support = (ctx.partial()
                ? "%s até %d de %s, contra média de %s no mesmo período de %s.".formatted(
                        InsightTexts.money(spent), ctx.period().daysElapsed(), InsightTexts.monthName(ctx.month()),
                        InsightTexts.money(average), ctx.averageRange())
                : "%s em %s, contra média de %s em %s.".formatted(InsightTexts.money(spent),
                        InsightTexts.monthName(ctx.month()), InsightTexts.money(average), ctx.averageRange()))
                + (top == null ? "" : " %s concentra %s%% do valor.".formatted(top.name(),
                        InsightTexts.percent(top.amount(), spent.amount())));

        Recommendation recommendation = top == null
                ? new Recommendation("Revisar " + lower, "%s somou %s em %s %s.".formatted(name,
                        InsightTexts.money(spent), InsightTexts.count(purchases, "compra", "compras"), ctx.inPeriod()))
                : new Recommendation("Revisar " + top.name(), "%s concentra %s%% do gasto da categoria (%s em %s)."
                        .formatted(top.name(), InsightTexts.percent(top.amount(), spent.amount()),
                                InsightTexts.money(new Money(top.amount(), ledger.currency())),
                                InsightTexts.count(top.count(), "compra", "compras")));

        String comparison = ctx.partial()
                ? "Comparamos o que você gastou em %s entre 1 e %d de %s com a média do mesmo período nos %s anteriores (%s)."
                        .formatted(lower, ctx.period().daysElapsed(), InsightTexts.monthName(ctx.month()),
                                InsightTexts.count(ctx.monthsUsed(), "mês", "meses"), ctx.averageRange())
                : "Comparamos o que você gastou em %s em %s com a média mensal dos %s anteriores (%s)."
                        .formatted(lower, InsightTexts.monthName(ctx.month()),
                                InsightTexts.count(ctx.monthsUsed(), "mês", "meses"), ctx.averageRange());

        InsightEvidence evidence = new InsightEvidence(
                comparisonFigures(ctx, spent, average, delta),
                ctx.series(w -> ledger.byCategory(CashFlowRole.EXPENSE, w).getOrDefault(category, ledger.zero())),
                merchants, comparison, ctx.limitations(true),
                List.of(new SourceRef(InsightSource.ANALYTICS_CATEGORIES, ctx.comparedRange().from(), current.to())));

        Map<String, String> filter = new LinkedHashMap<>();
        filter.put("from", current.from().toString());
        filter.put("to", current.to().toString());
        filter.put("type", "EXPENSE");
        filter.put("category", category);
        List<InsightAction> actions = new ArrayList<>();
        actions.add(InsightAction.primary(ActionKind.VIEW_TRANSACTIONS,
                purchases == 1 ? "Ver a transação" : "Ver as " + purchases + " transações", filter));
        actions.add(InsightAction.secondary(ActionKind.VIEW_CATEGORY, "Ver categoria",
                Map.of("month", ctx.key(), "category", category)));
        actions.addAll(DetectionContext.comingSoon());

        InsightContent content = new InsightContent(title, support, recommendation, evidence, actions,
                new InsightRefs(category, top == null ? null : top.key(), null, null, HealthDimension.SPENDING));
        return new InsightCandidate(InsightDetector.CATEGORY_SPIKE, ctx.key(), category, delta, null, true,
                ctx.confidence(), ctx.partial(), content);
    }

    /** D2: gasto total acima do padrão. */
    static List<InsightCandidate> spendingAbovePattern(DetectionContext ctx) {
        if (ctx.monthsUsed() < InsightRules.MIN_AVERAGE_MONTHS) {
            return List.of();
        }
        FlowLedger ledger = ctx.ledger();
        AnalyticsPeriod current = ctx.period().range();
        List<AnalyticsPeriod> base = ctx.plan().averageWindows();
        Money spent = ledger.total(CashFlowRole.EXPENSE, current);
        Money average = ledger.average(base, w -> ledger.total(CashFlowRole.EXPENSE, w));
        Money delta = spent.minus(average);
        if (average.amount().signum() <= 0 || delta.amount().compareTo(InsightRules.TOTAL_MIN_DELTA) < 0
                || !atLeastPercent(delta, average, InsightRules.TOTAL_MIN_PERCENT)) {
            return List.of();
        }

        List<ChangeBreakdown.Item> rises = ChangeBreakdown.of(ledger, current, base).items().stream()
                .filter(i -> i.kind() == ChangeKind.EXPENSE_CATEGORY && i.delta().amount().signum() > 0)
                .sorted(Comparator.comparing((ChangeBreakdown.Item i) -> i.delta().amount()).reversed()
                        .thenComparing(i -> i.categoryCode() == null ? "￿" : i.categoryCode()))
                .limit(3)
                .toList();
        List<String> parts = rises.stream().map(i -> "%s (+%s)".formatted(
                InsightTexts.lowerFirst(ctx.categoryName(i.categoryCode())), InsightTexts.money(i.delta()))).toList();
        String support = parts.size() == 1 ? "A maior alta foi em " + parts.getFirst() + "."
                : "As maiores altas foram em " + String.join(", ", parts.subList(0, parts.size() - 1)) + " e "
                        + parts.getLast() + ".";

        String title = "Seus gastos estão %s%% acima do seu padrão %s, cerca de %s a mais.".formatted(
                InsightTexts.percent(delta.amount(), average.amount()), ctx.inPeriod(), InsightTexts.approx(delta));
        String comparison = ctx.partial()
                ? "Comparamos o total gasto entre 1 e %d de %s com a média do mesmo período nos %s anteriores (%s)."
                        .formatted(ctx.period().daysElapsed(), InsightTexts.monthName(ctx.month()),
                                InsightTexts.count(ctx.monthsUsed(), "mês", "meses"), ctx.averageRange())
                : "Comparamos o total gasto em %s com a média mensal dos %s anteriores (%s)."
                        .formatted(InsightTexts.monthName(ctx.month()),
                                InsightTexts.count(ctx.monthsUsed(), "mês", "meses"), ctx.averageRange());

        List<Contributor> contributors = rises.stream().map(i -> new Contributor(ContributorKind.CATEGORY,
                i.categoryCode(), ctx.categoryName(i.categoryCode()), i.delta().amount(), null, null, null)).toList();
        InsightEvidence evidence = new InsightEvidence(comparisonFigures(ctx, spent, average, delta),
                ctx.series(w -> ledger.total(CashFlowRole.EXPENSE, w)), contributors, comparison,
                ctx.limitations(true),
                List.of(new SourceRef(InsightSource.ANALYTICS_SPENDING, ctx.comparedRange().from(), current.to())));

        List<InsightAction> actions = new ArrayList<>();
        actions.add(InsightAction.primary(ActionKind.VIEW_ANALYSIS, "Ver na análise", Map.of("month", ctx.key())));
        actions.addAll(DetectionContext.comingSoon());
        InsightContent content = new InsightContent(title, support, null, evidence, actions,
                new InsightRefs(rises.isEmpty() ? null : rises.getFirst().categoryCode(), null, null, null,
                        HealthDimension.SPENDING));
        return List.of(new InsightCandidate(InsightDetector.SPENDING_ABOVE_PATTERN, ctx.key(), "TOTAL", delta, null,
                true, ctx.confidence(), ctx.partial(), content));
    }

    /** D10: percentual alto de gastos sem categoria em P. */
    static List<InsightCandidate> uncategorizedShare(DetectionContext ctx) {
        FlowLedger ledger = ctx.ledger();
        AnalyticsPeriod current = ctx.period().range();
        Money total = ledger.total(CashFlowRole.EXPENSE, current);
        Money uncategorized = ledger.byCategory(CashFlowRole.EXPENSE, current).get(null);
        if (uncategorized == null || total.amount().signum() == 0
                || uncategorized.amount().compareTo(InsightRules.UNCATEGORIZED_MIN_AMOUNT) < 0
                || !atLeastPercent(uncategorized, total, InsightRules.UNCATEGORIZED_MIN_PERCENT)) {
            return List.of();
        }
        List<FlowEntry> entries = ledger.entries(CashFlowRole.EXPENSE, current).stream()
                .filter(e -> e.categoryCode() == null).toList();
        int count = entries.stream().mapToInt(FlowEntry::transactionCount).sum();
        String percent = InsightTexts.percent(uncategorized.amount(), total.amount());

        String title = "%s%% dos seus gastos %s estão sem categoria.".formatted(percent,
                ctx.partial() ? "deste mês" : "de " + InsightTexts.monthName(ctx.month()));
        String support = "%s, %s. Categorizar deixa as comparações e os insights mais precisos.".formatted(
                InsightTexts.count(count, "transação", "transações"), InsightTexts.money(uncategorized));
        String comparison = ctx.partial()
                ? "Somamos as despesas sem categoria entre 1 e %d de %s e comparamos com o total gasto no mesmo período."
                        .formatted(ctx.period().daysElapsed(), InsightTexts.monthName(ctx.month()))
                : "Somamos as despesas sem categoria de %s e comparamos com o total gasto no mês."
                        .formatted(InsightTexts.monthName(ctx.month()));

        List<KeyFigure> figures = List.of(
                new KeyFigure("uncategorized", "Sem categoria", FigureKind.MONEY, uncategorized.amount(),
                        current.from(), current.to()),
                new KeyFigure("total", "Total gasto", FigureKind.MONEY, total.amount(), current.from(), current.to()),
                new KeyFigure("share", "Participação", FigureKind.PERCENT,
                        FlowLedger.share(uncategorized, total), current.from(), current.to()),
                new KeyFigure("transactions", "Transações sem categoria", FigureKind.COUNT, BigDecimal.valueOf(count),
                        current.from(), current.to()));
        InsightEvidence evidence = new InsightEvidence(figures,
                ctx.series(w -> Objects.requireNonNullElse(ledger.byCategory(CashFlowRole.EXPENSE, w).get(null),
                        ledger.zero())),
                merchantContributors(ctx, entries, uncategorized), comparison, ctx.limitations(false),
                List.of(new SourceRef(InsightSource.ANALYTICS_CATEGORIES, current.from(), current.to())));

        InsightContent content = new InsightContent(title, support, null, evidence,
                List.of(InsightAction.primary(ActionKind.CATEGORIZE, "Categorizar transações",
                        Map.of("category", "none"))),
                InsightRefs.none());
        return List.of(new InsightCandidate(InsightDetector.UNCATEGORIZED_SHARE, ctx.key(), "UNCATEGORIZED",
                uncategorized, null, true, Confidence.MEDIUM, ctx.partial(), content));
    }

    /** Atual, média, diferença e variação %, cada um com o período. */
    static List<KeyFigure> comparisonFigures(DetectionContext ctx, Money current, Money average, Money delta) {
        AnalyticsPeriod range = ctx.period().range();
        AnalyticsPeriod base = new AnalyticsPeriod(ctx.plan().averageWindows().getFirst().from(),
                ctx.plan().averageWindows().getLast().to());
        return List.of(
                new KeyFigure("current", InsightTexts.capitalizedMonthName(ctx.month()) + ctx.dayCut(),
                        FigureKind.MONEY, current.amount(), range.from(), range.to()),
                new KeyFigure("average", "Média mensal " + ctx.averageRange() + ctx.dayCut(), FigureKind.MONEY,
                        average.amount(), base.from(), base.to()),
                new KeyFigure("difference", "Diferença", FigureKind.MONEY, delta.amount(), range.from(), range.to()),
                new KeyFigure("percent", "Variação", FigureKind.PERCENT,
                        delta.amount().multiply(HUNDRED).divide(average.amount(), 1, RoundingMode.HALF_EVEN),
                        range.from(), range.to()));
    }

    /** Comerciantes por valor no período (até 5), com compras e participação. */
    static List<Contributor> merchantContributors(DetectionContext ctx, List<FlowEntry> entries, Money total) {
        Map<String, BigDecimal> amounts = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (FlowEntry e : entries) {
            amounts.merge(e.merchantKey(), e.amount().amount(), BigDecimal::add);
            counts.merge(e.merchantKey(), e.transactionCount(), Integer::sum);
        }
        return amounts.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<String, BigDecimal> e) -> e.getValue()).reversed()
                        .thenComparing(e -> e.getKey() == null ? "￿" : e.getKey()))
                .limit(TOP_CONTRIBUTORS)
                .map(e -> new Contributor(ContributorKind.MERCHANT, e.getKey(), ctx.merchantName(e.getKey()),
                        e.getValue(), counts.get(e.getKey()),
                        FlowLedger.share(new Money(e.getValue(), total.currency()), total), null))
                .toList();
    }

    /** {@code part ÷ whole ≥ percent%}, sem arredondar. */
    static boolean atLeastPercent(Money part, Money whole, BigDecimal percent) {
        return part.amount().multiply(HUNDRED).compareTo(percent.multiply(whole.amount())) >= 0;
    }

    /** {@code part ÷ R ≥ percent%}; sem R (nenhum mês fechado com despesa), a condição relativa não se aplica. */
    static boolean atLeastShareOfReference(DetectionContext ctx, Money part, BigDecimal percent) {
        Money reference = ctx.reference();
        return reference == null || reference.amount().signum() == 0 || atLeastPercent(part, reference, percent);
    }
}
