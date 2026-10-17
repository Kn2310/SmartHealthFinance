package com.smarthealthfinance.insights.domain.service;

import com.smarthealthfinance.analytics.domain.enums.Recurrence;
import com.smarthealthfinance.analytics.domain.model.FlowEntry;
import com.smarthealthfinance.analytics.domain.service.FlowLedger;
import com.smarthealthfinance.analytics.domain.service.IncomeSources;
import com.smarthealthfinance.analytics.domain.valueobject.AnalysisPeriod;
import com.smarthealthfinance.analytics.domain.valueobject.AnalyticsPeriod;
import com.smarthealthfinance.health.domain.enums.HealthDimension;
import com.smarthealthfinance.insights.domain.enums.ActionKind;
import com.smarthealthfinance.insights.domain.enums.FigureKind;
import com.smarthealthfinance.insights.domain.enums.InsightDetector;
import com.smarthealthfinance.insights.domain.enums.InsightSource;
import com.smarthealthfinance.insights.domain.enums.LimitationCode;
import com.smarthealthfinance.insights.domain.model.ChangeLine;
import com.smarthealthfinance.insights.domain.model.InsightAction;
import com.smarthealthfinance.insights.domain.model.InsightCandidate;
import com.smarthealthfinance.insights.domain.model.InsightContent;
import com.smarthealthfinance.insights.domain.model.InsightEvidence;
import com.smarthealthfinance.insights.domain.model.InsightRefs;
import com.smarthealthfinance.insights.domain.model.KeyFigure;
import com.smarthealthfinance.insights.domain.model.Limitation;
import com.smarthealthfinance.insights.domain.model.SourceRef;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.enums.CashFlowRole;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Detectores de receita (ADR-0021 §2): D5 {@code RECURRING_INCOME_MISSING}/{@code RECURRING_INCOME_LOWER} e D6
 * {@code NEW_INCOME_SOURCE}. Fonte, recorrência e dia típico são os da aba Receitas (ADR-0015 §6, {@link IncomeSources}).
 */
final class IncomeDetectors {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private IncomeDetectors() {
    }

    /** D5: fonte recorrente até P − 1 que não entrou, ou entrou bem abaixo, em P. */
    static List<InsightCandidate> recurringIncome(DetectionContext ctx) {
        Optional<YearMonth> first = ctx.firstActivity().map(YearMonth::from);
        YearMonth previousMonth = ctx.month().minusMonths(1);
        if (first.isEmpty() || previousMonth.isBefore(first.get())) {
            return List.of();
        }
        FlowLedger ledger = ctx.ledger();
        AnalysisPeriod previous = AnalysisPeriod.resolve(previousMonth.toString(), ctx.today());
        List<YearMonth> history = previous.lastMonths(IncomeSources.HISTORY_MONTHS);

        List<InsightCandidate> candidates = new ArrayList<>();
        for (IncomeSources.Source source : IncomeSources.of(ledger, previous)) {
            if (source.recurrence() != Recurrence.RECURRING || source.typicalDay() == null) {
                continue;
            }
            boolean dateReady = !ctx.partial()
                    || ctx.today().getDayOfMonth() > source.typicalDay() + InsightRules.INCOME_GRACE_DAYS;
            if (!dateReady) {
                continue;
            }
            String key = key(source);
            List<YearMonth> received = new ArrayList<>();
            Money sum = ledger.zero();
            for (YearMonth month : history) {
                Money inMonth = amount(ledger, key, AnalyticsPeriod.of(month));
                if (inMonth.amount().signum() > 0) {
                    received.add(month);
                    sum = sum.plus(inMonth);
                }
            }
            if (received.isEmpty()) {
                continue;
            }
            Money expected = ledger.divide(sum, received.size());
            if (expected.amount().compareTo(InsightRules.INCOME_MIN_EXPECTED) < 0) {
                continue;
            }
            Money now = amount(ledger, key, ctx.period().range());
            Money shortfall = expected.minus(now);
            if (now.amount().signum() == 0) {
                candidates.add(recurringIncomeCandidate(ctx, InsightDetector.RECURRING_INCOME_MISSING, source, key,
                        expected, now, expected, received, history));
            }
            else if (now.amount().multiply(HUNDRED).compareTo(InsightRules.INCOME_LOWER_PERCENT
                    .multiply(expected.amount())) < 0
                    && shortfall.amount().compareTo(InsightRules.INCOME_MIN_SHORTFALL) >= 0) {
                candidates.add(recurringIncomeCandidate(ctx, InsightDetector.RECURRING_INCOME_LOWER, source, key,
                        expected, now, shortfall, received, history));
            }
        }
        return candidates;
    }

    private static InsightCandidate recurringIncomeCandidate(DetectionContext ctx, InsightDetector detector,
                                                             IncomeSources.Source source, String key, Money expected,
                                                             Money now, Money impact, List<YearMonth> received,
                                                             List<YearMonth> history) {
        FlowLedger ledger = ctx.ledger();
        String name = sourceName(ctx, source);
        String month = InsightTexts.monthName(ctx.month());
        boolean missing = detector == InsightDetector.RECURRING_INCOME_MISSING;
        String title;
        String support;
        if (missing) {
            title = ctx.partial() ? "Ainda não identificamos %s neste mês.".formatted(name)
                    : "Não identificamos %s em %s.".formatted(name, month);
            support = "Ela costuma entrar por volta do dia %d, em média %s. Se já recebeu, importe o extrato mais recente."
                    .formatted(source.typicalDay(), InsightTexts.money(expected));
        }
        else {
            title = "%s veio cerca de %s abaixo da média %s.".formatted(name, InsightTexts.approx(impact),
                    ctx.inPeriod());
            support = (ctx.partial() ? "%s até agora, contra média de %s." : "%s em " + month + ", contra média de %s.")
                    .formatted(InsightTexts.money(now), InsightTexts.money(expected));
        }
        AnalyticsPeriod historyRange = AnalyticsPeriod.months(history.getFirst(), history.getLast());
        AnalyticsPeriod current = ctx.period().range();
        List<KeyFigure> figures = List.of(
                new KeyFigure("expected", "Média quando entrou", FigureKind.MONEY, expected.amount(),
                        historyRange.from(), historyRange.to()),
                new KeyFigure("received", "Recebido " + ctx.inPeriod(), FigureKind.MONEY, now.amount(),
                        current.from(), current.to()),
                new KeyFigure("difference", "Diferença", FigureKind.MONEY, expected.minus(now).amount(),
                        current.from(), current.to()),
                new KeyFigure("typicalDay", "Dia típico", FigureKind.COUNT, BigDecimal.valueOf(source.typicalDay()),
                        historyRange.from(), historyRange.to()));
        String comparison = "Esta fonte entrou em %d dos %d meses de %s, em média %s, por volta do dia %d.".formatted(
                received.size(), history.size(), InsightTexts.range(history), InsightTexts.money(expected),
                source.typicalDay());
        List<Limitation> limitations = new ArrayList<>();
        if (ctx.partial()) {
            limitations.add(ctx.partialMonth());
        }
        limitations.add(new Limitation(LimitationCode.IMPORT_LAG, "Pode ser só um extrato ainda não importado."));
        InsightEvidence evidence = new InsightEvidence(figures, ctx.series(w -> amount(ledger, key, w)), List.of(),
                comparison, limitations,
                List.of(new SourceRef(InsightSource.ANALYTICS_INCOME_SOURCES, historyRange.from(), current.to())));
        List<InsightAction> actions = missing
                ? List.of(InsightAction.primary(ActionKind.IMPORT_STATEMENT, "Importar extrato", Map.of()),
                        InsightAction.secondary(ActionKind.VIEW_INCOME, "Ver receitas", Map.of("month", ctx.key())))
                : List.of(InsightAction.primary(ActionKind.VIEW_INCOME, "Ver receitas", Map.of("month", ctx.key())));
        ChangeLine line = missing
                ? new ChangeLine("Receita não identificada: " + name, "média de " + InsightTexts.money(expected),
                        "costuma entrar por volta do dia " + source.typicalDay())
                : new ChangeLine(InsightTexts.capitalizeFirst(name) + " abaixo da média",
                        ctx.amountInPeriod(now), "média de " + InsightTexts.money(expected));
        InsightContent content = new InsightContent(title, support, InsightTexts.capitalizeFirst(name), line, null,
                evidence, actions, new InsightRefs(null, null, key, null, HealthDimension.CASH_FLOW));
        return new InsightCandidate(detector, ctx.key(), key, impact, null, true, ctx.confidence(), ctx.partial(),
                content);
    }

    /** D6: fonte com entrada em P e nenhuma nos 6 meses anteriores, com histórico de 3+ meses fechados. */
    static List<InsightCandidate> newIncomeSources(DetectionContext ctx) {
        Optional<YearMonth> first = ctx.firstActivity().map(YearMonth::from);
        YearMonth p = ctx.month();
        if (first.isEmpty() || p.minusMonths(InsightRules.NEW_SOURCE_MIN_HISTORY_MONTHS).isBefore(first.get())) {
            return List.of();
        }
        FlowLedger ledger = ctx.ledger();
        AnalyticsPeriod before = AnalyticsPeriod.months(p.minusMonths(InsightRules.NEW_SOURCE_LOOKBACK_MONTHS),
                p.minusMonths(1));
        AnalyticsPeriod current = ctx.period().range();

        List<InsightCandidate> candidates = new ArrayList<>();
        for (IncomeSources.Source source : IncomeSources.of(ledger, ctx.period())) {
            String key = key(source);
            if (source.amount().amount().compareTo(InsightRules.NEW_SOURCE_MIN_AMOUNT) < 0
                    || amount(ledger, key, before).amount().signum() != 0) {
                continue;
            }
            String name = sourceName(ctx, source);
            LocalDate firstDay = ledger.entries(CashFlowRole.INCOME, current).stream()
                    .filter(e -> key.equals(IncomeSources.sourceKey(e))).map(FlowEntry::day)
                    .min(Comparator.naturalOrder()).orElseThrow();
            String title = "Você recebeu %s de uma nova fonte: %s.".formatted(InsightTexts.money(source.amount()),
                    name);
            String support = "Entrada em %s. Nenhuma receita dessa fonte nos 6 meses anteriores.".formatted(
                    InsightTexts.dayMonth(firstDay));
            List<KeyFigure> figures = List.of(
                    new KeyFigure("amount", "Recebido " + ctx.inPeriod(), FigureKind.MONEY, source.amount().amount(),
                            current.from(), current.to()),
                    new KeyFigure("transactions", "Entradas", FigureKind.COUNT,
                            BigDecimal.valueOf(source.transactionCount()), current.from(), current.to()));
            String comparison = "Procuramos receitas de fontes que não apareceram nos 6 meses anteriores (%s)."
                    .formatted(InsightTexts.range(List.of(p.minusMonths(6), p.minusMonths(1))));
            InsightEvidence evidence = new InsightEvidence(figures, ctx.series(w -> amount(ledger, key, w)), List.of(),
                    comparison, ctx.partial() ? List.of(ctx.partialMonth()) : List.of(),
                    List.of(new SourceRef(InsightSource.ANALYTICS_INCOME_SOURCES, before.from(), current.to())));
            ChangeLine line = new ChangeLine("Nova receita: " + name, "+ " + InsightTexts.money(source.amount()),
                    InsightTexts.dayMonth(firstDay));
            InsightContent content = new InsightContent(title, support, InsightTexts.capitalizeFirst(name), line,
                    null, evidence,
                    List.of(InsightAction.primary(ActionKind.VIEW_INCOME, "Ver receitas", Map.of("month", ctx.key()))),
                    new InsightRefs(null, null, key, null, HealthDimension.CASH_FLOW));
            candidates.add(new InsightCandidate(InsightDetector.NEW_INCOME_SOURCE, ctx.key(), key, source.amount(),
                    null, true, ctx.confidence(), ctx.partial(), content));
        }
        return candidates;
    }

    private static String key(IncomeSources.Source source) {
        return source.merchantKey() != null ? "merchant:" + source.merchantKey()
                : "category:" + (source.categoryCode() == null ? "" : source.categoryCode());
    }

    private static Money amount(FlowLedger ledger, String key, AnalyticsPeriod window) {
        return ledger.entries(CashFlowRole.INCOME, window).stream()
                .filter(e -> key.equals(IncomeSources.sourceKey(e)))
                .map(FlowEntry::amount).reduce(ledger.zero(), Money::plus);
    }

    private static String sourceName(DetectionContext ctx, IncomeSources.Source source) {
        if (source.merchantName() != null) {
            return source.merchantName();
        }
        return source.categoryCode() == null ? "receita sem categoria"
                : InsightTexts.lowerFirst(ctx.categoryName(source.categoryCode()));
    }
}
