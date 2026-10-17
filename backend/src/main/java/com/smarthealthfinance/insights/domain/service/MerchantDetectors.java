package com.smarthealthfinance.insights.domain.service;

import com.smarthealthfinance.analytics.domain.model.ExpenseItem;
import com.smarthealthfinance.analytics.domain.model.FlowEntry;
import com.smarthealthfinance.analytics.domain.service.FlowLedger;
import com.smarthealthfinance.analytics.domain.valueobject.AnalyticsPeriod;
import com.smarthealthfinance.health.domain.enums.HealthDimension;
import com.smarthealthfinance.insights.domain.enums.ActionKind;
import com.smarthealthfinance.insights.domain.enums.ContributorKind;
import com.smarthealthfinance.insights.domain.enums.FigureKind;
import com.smarthealthfinance.insights.domain.enums.InsightDetector;
import com.smarthealthfinance.insights.domain.enums.InsightSource;
import com.smarthealthfinance.insights.domain.model.Contributor;
import com.smarthealthfinance.insights.domain.model.ChangeLine;
import com.smarthealthfinance.insights.domain.model.InsightAction;
import com.smarthealthfinance.insights.domain.model.InsightCandidate;
import com.smarthealthfinance.insights.domain.model.InsightContent;
import com.smarthealthfinance.insights.domain.model.InsightEvidence;
import com.smarthealthfinance.insights.domain.model.InsightRefs;
import com.smarthealthfinance.insights.domain.model.KeyFigure;
import com.smarthealthfinance.insights.domain.model.SourceRef;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.enums.CashFlowRole;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Detectores por comerciante e por compra (ADR-0021 §2): D3 {@code NEW_RECURRING_MERCHANT} e D4
 * {@code UNUSUAL_EXPENSE}.
 */
final class MerchantDetectors {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    /** Meses antes de P que D3 precisa enxergar: série nova (até P − 3) + 3 meses de ausência. */
    private static final int RECURRING_LOOKBACK = 6;

    private MerchantDetectors() {
    }

    /** Uma cobrança mensal: o mês, o dia e o valor. */
    private record Charge(YearMonth month, LocalDate day, Money amount, String categoryCode) {}

    /** D3: comerciante identificado com 1 cobrança por mês em 3+ meses seguidos, recentes, depois de 3 sem nada. */
    static List<InsightCandidate> newRecurringMerchants(DetectionContext ctx) {
        FlowLedger ledger = ctx.ledger();
        YearMonth p = ctx.month();
        Optional<YearMonth> first = ctx.firstActivity().map(YearMonth::from);
        if (first.isEmpty()) {
            return List.of();
        }
        // Entradas de despesa por comerciante e mês, de P − 6 a P.
        Map<String, Map<YearMonth, List<FlowEntry>>> byMerchant = new TreeMap<>();
        for (int back = RECURRING_LOOKBACK; back >= 0; back--) {
            YearMonth month = p.minusMonths(back);
            for (FlowEntry e : ledger.entries(CashFlowRole.EXPENSE, ctx.period().historyRange(month))) {
                if (e.merchantKey() != null) {
                    byMerchant.computeIfAbsent(e.merchantKey(), k -> new TreeMap<>())
                            .computeIfAbsent(month, k -> new ArrayList<>()).add(e);
                }
            }
        }

        List<InsightCandidate> candidates = new ArrayList<>();
        for (Map.Entry<String, Map<YearMonth, List<FlowEntry>>> merchant : byMerchant.entrySet()) {
            Map<YearMonth, List<FlowEntry>> months = merchant.getValue();
            int inP = count(months.get(p));
            YearMonth end;
            if (inP == 1) {
                end = p;
            }
            else if (inP == 0 && ctx.partial()) {
                end = p.minusMonths(1);
            }
            else {
                continue;
            }
            List<Charge> run = new ArrayList<>();
            for (YearMonth month = end; !month.isBefore(p.minusMonths(RECURRING_LOOKBACK))
                    && count(months.get(month)) == 1; month = month.minusMonths(1)) {
                FlowEntry only = months.get(month).getFirst();
                run.addFirst(new Charge(month, only.day(), only.amount(), only.categoryCode()));
            }
            if (run.size() < InsightRules.RECURRING_MIN_MONTHS) {
                continue;
            }
            YearMonth start = run.getFirst().month();
            YearMonth absentFrom = start.minusMonths(InsightRules.RECURRING_ABSENT_MONTHS);
            if (start.isBefore(p.minusMonths(3)) || absentFrom.isBefore(first.get())) {
                continue;
            }
            boolean absent = true;
            for (YearMonth month = absentFrom; month.isBefore(start); month = month.plusMonths(1)) {
                absent &= count(months.get(month)) == 0;
            }
            Money median = median(run.stream().map(Charge::amount).toList());
            if (!absent || median.amount().compareTo(InsightRules.RECURRING_MIN_AMOUNT) < 0
                    || !run.stream().allMatch(c -> withinTolerance(c.amount(), median))) {
                continue;
            }
            candidates.add(newRecurringMerchant(ctx, merchant.getKey(), run, median, absentFrom));
        }
        return candidates;
    }

    private static InsightCandidate newRecurringMerchant(DetectionContext ctx, String merchantKey, List<Charge> run,
                                                         Money median, YearMonth absentFrom) {
        FlowLedger ledger = ctx.ledger();
        String name = ctx.merchantName(merchantKey);
        YearMonth start = run.getFirst().month();
        YearMonth end = run.getLast().month();
        Money min = run.stream().map(Charge::amount).min(Comparator.comparing(Money::amount)).orElseThrow();
        Money max = run.stream().map(Charge::amount).max(Comparator.comparing(Money::amount)).orElseThrow();
        int day = typicalDay(run);

        String title = "%s cobra cerca de %s todo mês desde %s.".formatted(name, InsightTexts.approx(median),
                InsightTexts.monthName(start));
        String support = min.equals(max)
                ? "Pode ser uma assinatura nova: %d cobranças de %s, perto do dia %d.".formatted(run.size(),
                        InsightTexts.money(min), day)
                : "Pode ser uma assinatura nova: %d cobranças entre %s e %s, perto do dia %d.".formatted(run.size(),
                        InsightTexts.money(min), InsightTexts.money(max), day);
        AnalyticsPeriod series = new AnalyticsPeriod(start.atDay(1), ctx.period().historyRange(end).to());
        List<KeyFigure> figures = List.of(
                new KeyFigure("median", "Valor mensal típico", FigureKind.MONEY, median.amount(), series.from(),
                        series.to()),
                new KeyFigure("charges", "Cobranças", FigureKind.COUNT, BigDecimal.valueOf(run.size()), series.from(),
                        series.to()));
        List<Contributor> charges = run.stream().map(c -> new Contributor(ContributorKind.CHARGE, merchantKey, name,
                c.amount().amount(), 1, null, c.day())).toList();
        String comparison = "Procuramos comerciantes com uma cobrança por mês, de valor parecido (até 10% de "
                + "diferença), em 3 meses seguidos ou mais, e sem cobranças nos 3 meses anteriores.";
        InsightEvidence evidence = new InsightEvidence(figures,
                ctx.series(w -> ledger.byMerchant(CashFlowRole.EXPENSE, w).getOrDefault(merchantKey, ledger.zero())),
                charges, comparison, List.of(),
                List.of(new SourceRef(InsightSource.ANALYTICS_MERCHANTS, absentFrom.atDay(1), series.to())));
        ChangeLine line = new ChangeLine("Nova cobrança mensal: " + name, InsightTexts.money(median) + "/mês",
                "desde " + InsightTexts.monthName(start));
        InsightContent content = new InsightContent(title, support, name, line, null, evidence,
                List.of(InsightAction.primary(ActionKind.VIEW_MERCHANTS, "Ver comerciantes",
                        Map.of("month", ctx.key()))),
                new InsightRefs(mainCategory(run), merchantKey, null, null, HealthDimension.SPENDING));
        return new InsightCandidate(InsightDetector.NEW_RECURRING_MERCHANT, start.toString(), merchantKey, median,
                null, true, ctx.confidence(), ctx.partial(), content);
    }

    /** D4: até 3 compras categorizadas de P muito acima do ticket médio da categoria. */
    static List<InsightCandidate> unusualExpenses(DetectionContext ctx) {
        FlowLedger ledger = ctx.ledger();
        Optional<YearMonth> first = ctx.firstActivity().map(YearMonth::from);
        List<YearMonth> history = new ArrayList<>();
        for (int back = InsightRules.UNUSUAL_HISTORY_MONTHS; back >= 1; back--) {
            YearMonth month = ctx.month().minusMonths(back);
            if (first.isPresent() && !month.isBefore(first.get())) {
                history.add(month);
            }
        }
        if (history.isEmpty()) {
            return List.of();
        }
        AnalyticsPeriod range = AnalyticsPeriod.months(history.getFirst(), history.getLast());

        record Unusual(ExpenseItem item, Money ticket, int purchases, int months, Money impact) {}
        List<Unusual> found = new ArrayList<>();
        for (ExpenseItem item : ctx.input().largestExpenses()) {
            String category = item.categoryCode();
            if (category == null || item.amount().amount().compareTo(InsightRules.UNUSUAL_MIN_AMOUNT) < 0) {
                continue;
            }
            int purchases = ledger.countByCategory(CashFlowRole.EXPENSE, range).getOrDefault(category, 0);
            if (purchases < InsightRules.UNUSUAL_MIN_PURCHASES) {
                continue;
            }
            Money ticket = ledger.divide(ledger.byCategory(CashFlowRole.EXPENSE, range).get(category), purchases);
            if (item.amount().amount().compareTo(ticket.amount().multiply(InsightRules.UNUSUAL_MIN_MULTIPLE)) < 0) {
                continue;
            }
            int months = (int) history.stream().filter(m -> ledger.countByCategory(CashFlowRole.EXPENSE,
                    AnalyticsPeriod.of(m)).getOrDefault(category, 0) > 0).count();
            found.add(new Unusual(item, ticket, purchases, months, item.amount().minus(ticket)));
        }
        return found.stream()
                .sorted(Comparator.comparing((Unusual u) -> u.impact().amount()).reversed()
                        .thenComparing(u -> u.item().transactionId()))
                .limit(InsightRules.UNUSUAL_MAX)
                .map(u -> unusualExpense(ctx, u.item(), u.ticket(), u.purchases(), u.months(), u.impact(), history,
                        range))
                .toList();
    }

    private static InsightCandidate unusualExpense(DetectionContext ctx, ExpenseItem item, Money ticket,
                                                   int purchases, int months, Money impact, List<YearMonth> history,
                                                   AnalyticsPeriod range) {
        FlowLedger ledger = ctx.ledger();
        String category = item.categoryCode();
        String lower = InsightTexts.lowerFirst(ctx.categoryName(category));
        String merchant = Objects.requireNonNullElse(item.merchantName(), DetectionContext.UNKNOWN_MERCHANT);
        BigDecimal multiple = item.amount().amount().divide(ticket.amount(), 1, RoundingMode.HALF_EVEN);

        String title = "Uma compra de %s em %s ficou %s vezes acima do seu valor médio.".formatted(
                InsightTexts.money(item.amount()), lower, InsightTexts.oneDecimal(multiple));
        String support = "%s em %s. Seu valor médio em %s é %s (%s em %s).".formatted(merchant,
                InsightTexts.dayMonth(item.occurredOn()), lower, InsightTexts.money(ticket),
                InsightTexts.count(purchases, "compra", "compras"), InsightTexts.range(history));
        String comparison = "Comparamos esta compra com o valor médio das %s em %s nos meses fechados de %s."
                .formatted(InsightTexts.count(purchases, "compra", "compras"), lower, InsightTexts.range(history));
        List<KeyFigure> figures = List.of(
                new KeyFigure("amount", "Valor da compra", FigureKind.MONEY, item.amount().amount(),
                        item.occurredOn(), item.occurredOn()),
                new KeyFigure("ticket", "Valor médio em " + lower, FigureKind.MONEY, ticket.amount(), range.from(),
                        range.to()),
                new KeyFigure("multiple", "Vezes o valor médio", FigureKind.RATIO, multiple, range.from(),
                        item.occurredOn()),
                new KeyFigure("purchases", "Compras na média", FigureKind.COUNT, BigDecimal.valueOf(purchases),
                        range.from(), range.to()));
        InsightEvidence evidence = new InsightEvidence(figures,
                ctx.series(w -> ledger.byCategory(CashFlowRole.EXPENSE, w).getOrDefault(category, ledger.zero())),
                List.of(new Contributor(ContributorKind.TRANSACTION, item.transactionId().toString(), merchant,
                        item.amount().amount(), 1, null, item.occurredOn())),
                comparison, ctx.partial() ? List.of(ctx.partialMonth()) : List.of(),
                List.of(new SourceRef(InsightSource.TRANSACTIONS_LARGEST_EXPENSES, ctx.period().from(),
                                ctx.period().to()),
                        new SourceRef(InsightSource.ANALYTICS_CATEGORIES, range.from(), range.to())));
        InsightContent content = new InsightContent(title, support, ctx.categoryName(category), null, null, evidence,
                List.of(InsightAction.primary(ActionKind.VIEW_TRANSACTION, "Ver transação",
                                Map.of("transactionId", item.transactionId().toString())),
                        InsightAction.secondary(ActionKind.VIEW_CATEGORY, "Ver categoria",
                                Map.of("month", ctx.key(), "category", category))),
                new InsightRefs(category, null, null, item.transactionId(), HealthDimension.SPENDING));
        return new InsightCandidate(InsightDetector.UNUSUAL_EXPENSE, ctx.key(), item.transactionId().toString(),
                impact, null, true, ctx.confidence(months), ctx.partial(), content);
    }

    private static int count(List<FlowEntry> entries) {
        return entries == null ? 0 : entries.stream().mapToInt(FlowEntry::transactionCount).sum();
    }

    /** Mediana com 2 casas (HALF_EVEN): o do meio, ou a média dos dois do meio. */
    static Money median(List<Money> values) {
        List<Money> sorted = values.stream().sorted(Comparator.comparing(Money::amount)).toList();
        int middle = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            Money only = sorted.get(middle);
            return new Money(only.amount().setScale(2, RoundingMode.HALF_EVEN), only.currency());
        }
        BigDecimal sum = sorted.get(middle - 1).amount().add(sorted.get(middle).amount());
        return new Money(sum.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_EVEN), sorted.get(middle).currency());
    }

    /** |valor − mediana| ≤ 10% da mediana, sem arredondar. */
    private static boolean withinTolerance(Money value, Money median) {
        return value.amount().subtract(median.amount()).abs().multiply(HUNDRED)
                .compareTo(InsightRules.RECURRING_TOLERANCE_PERCENT.multiply(median.amount())) <= 0;
    }

    /** Dia do mês mais frequente; empate: o menor. */
    private static int typicalDay(List<Charge> run) {
        Map<Integer, Integer> frequency = new TreeMap<>();
        run.forEach(c -> frequency.merge(c.day().getDayOfMonth(), 1, Integer::sum));
        return frequency.entrySet().stream().reduce((best, next) -> next.getValue() > best.getValue() ? next : best)
                .map(Map.Entry::getKey).orElseThrow();
    }

    /** Categoria de maior valor nas cobranças; empate: o menor código; nula se nenhuma categorizada. */
    private static String mainCategory(List<Charge> run) {
        Map<String, BigDecimal> totals = new TreeMap<>();
        run.stream().filter(c -> c.categoryCode() != null)
                .forEach(c -> totals.merge(c.categoryCode(), c.amount().amount(), BigDecimal::add));
        return totals.entrySet().stream()
                .reduce((best, next) -> next.getValue().compareTo(best.getValue()) > 0 ? next : best)
                .map(Map.Entry::getKey).orElse(null);
    }
}
