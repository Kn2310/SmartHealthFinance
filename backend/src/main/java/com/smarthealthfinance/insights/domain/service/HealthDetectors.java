package com.smarthealthfinance.insights.domain.service;

import com.smarthealthfinance.health.domain.enums.HealthDimension;
import com.smarthealthfinance.health.domain.model.DimensionResult;
import com.smarthealthfinance.health.domain.model.Evidence;
import com.smarthealthfinance.health.domain.model.HealthResult;
import com.smarthealthfinance.insights.domain.enums.ActionKind;
import com.smarthealthfinance.insights.domain.enums.FigureKind;
import com.smarthealthfinance.insights.domain.enums.InsightDetector;
import com.smarthealthfinance.insights.domain.enums.InsightSource;
import com.smarthealthfinance.insights.domain.enums.LimitationCode;
import com.smarthealthfinance.insights.domain.model.InsightAction;
import com.smarthealthfinance.insights.domain.model.InsightCandidate;
import com.smarthealthfinance.insights.domain.model.InsightContent;
import com.smarthealthfinance.insights.domain.model.InsightEvidence;
import com.smarthealthfinance.insights.domain.model.InsightRefs;
import com.smarthealthfinance.insights.domain.model.KeyFigure;
import com.smarthealthfinance.insights.domain.model.Limitation;
import com.smarthealthfinance.insights.domain.model.SeriesPoint;
import com.smarthealthfinance.insights.domain.model.SourceRef;
import com.smarthealthfinance.shared.domain.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Detectores sobre o snapshot do Health (ADR-0021 §2): D7 {@code HEALTH_SCORE_CHANGE}, D8
 * {@code HEALTH_DIMENSION_CHANGE} e D9 {@code LOW_RESERVE}. Os números são os do snapshot gravado (ADR-0018), nunca
 * recalculados aqui.
 */
final class HealthDetectors {

    private HealthDetectors() {
    }

    /** D7: o score de P mudou 5 pontos ou mais contra P − 1 (mesma versão do algoritmo). */
    static List<InsightCandidate> scoreChange(DetectionContext ctx) {
        HealthResult now = ctx.input().health().get(ctx.month());
        HealthResult before = ctx.input().health().get(ctx.month().minusMonths(1));
        if (now == null || before == null || now.score() == null || before.score() == null
                || now.variation() == null || Math.abs(now.variation()) < InsightRules.SCORE_MIN_CHANGE) {
            return List.of();
        }
        int change = Math.abs(now.variation());
        String previousName = InsightTexts.monthName(before.month());
        String title = "Seu score %s %s em relação a %s.".formatted(now.variation() > 0 ? "subiu" : "caiu",
                InsightTexts.count(change, "ponto", "pontos"), previousName);
        String support = "De %d para %d pontos.".formatted(before.score(), now.score())
                + (now.summary() == null ? "" : " " + now.summary());
        LocalDate from = before.month().atDay(1);
        List<KeyFigure> figures = List.of(
                new KeyFigure("score", "Score " + InsightTexts.monthName(now.month()), FigureKind.POINTS,
                        BigDecimal.valueOf(now.score()), now.month().atDay(1), now.asOf()),
                new KeyFigure("previousScore", "Score " + previousName, FigureKind.POINTS,
                        BigDecimal.valueOf(before.score()), from, before.asOf()),
                new KeyFigure("variation", "Variação", FigureKind.POINTS, BigDecimal.valueOf(now.variation()), from,
                        now.asOf()));
        String comparison = "Comparamos o score de %s com o de %s, calculados com o mesmo algoritmo (%s).".formatted(
                InsightTexts.monthName(now.month()), previousName, now.algorithmVersion());
        InsightEvidence evidence = new InsightEvidence(figures, series(ctx, HealthResult::score), List.of(),
                comparison, limitations(ctx, now), List.of(new SourceRef(InsightSource.HEALTH_SNAPSHOT, from,
                        now.asOf())));
        InsightContent content = new InsightContent(title, support, null, evidence, List.of(viewHealth()),
                InsightRefs.none());
        return List.of(new InsightCandidate(InsightDetector.HEALTH_SCORE_CHANGE, ctx.key(), "SCORE", null, change,
                change >= InsightRules.SCORE_IMPORTANT_CHANGE, now.confidence(), now.partial(), content));
    }

    /** D8: a dimensão (OK em P e P − 1) que mais mudou, com 10 pontos ou mais. */
    static List<InsightCandidate> dimensionChange(DetectionContext ctx) {
        HealthResult now = ctx.input().health().get(ctx.month());
        HealthResult before = ctx.input().health().get(ctx.month().minusMonths(1));
        if (now == null || before == null) {
            return List.of();
        }
        record Change(HealthDimension dimension, int from, int to) {
            int size() {
                return Math.abs(to - from);
            }
        }
        Optional<Change> largest = now.dimensions().stream()
                .filter(DimensionResult::isOk)
                .flatMap(d -> dimension(before, d.dimension()).filter(DimensionResult::isOk).stream()
                        .map(b -> new Change(d.dimension(), b.score(), d.score())))
                .filter(c -> c.size() >= InsightRules.DIMENSION_MIN_CHANGE)
                .min(Comparator.comparing(Change::size).reversed()
                        .thenComparing(c -> -c.dimension().weight())
                        .thenComparing(c -> c.dimension().ordinal()));
        if (largest.isEmpty()) {
            return List.of();
        }
        Change change = largest.get();
        DimensionResult current = dimension(now, change.dimension()).orElseThrow();
        String previousName = InsightTexts.monthName(before.month());
        String title = "A nota de %s passou de %d para %d em relação a %s.".formatted(
                InsightTexts.lowerFirst(change.dimension().label()), change.from(), change.to(), previousName);
        LocalDate from = before.month().atDay(1);
        List<KeyFigure> figures = List.of(
                new KeyFigure("score", change.dimension().label() + " em " + InsightTexts.monthName(now.month()),
                        FigureKind.POINTS, BigDecimal.valueOf(change.to()), now.month().atDay(1), now.asOf()),
                new KeyFigure("previousScore", change.dimension().label() + " em " + previousName, FigureKind.POINTS,
                        BigDecimal.valueOf(change.from()), from, before.asOf()),
                new KeyFigure("variation", "Variação", FigureKind.POINTS,
                        BigDecimal.valueOf(change.to() - change.from()), from, now.asOf()));
        String comparison = "Comparamos a nota de %s em %s com a de %s, calculadas com o mesmo algoritmo (%s)."
                .formatted(InsightTexts.lowerFirst(change.dimension().label()), InsightTexts.monthName(now.month()),
                        previousName, now.algorithmVersion());
        InsightEvidence evidence = new InsightEvidence(figures,
                series(ctx, r -> dimension(r, change.dimension()).map(DimensionResult::score).orElse(null)),
                List.of(), comparison, limitations(ctx, now),
                List.of(new SourceRef(InsightSource.HEALTH_SNAPSHOT, from, now.asOf())));
        InsightContent content = new InsightContent(title, current.explanation(), null, evidence,
                List.of(viewHealth()), new InsightRefs(null, null, null, null, change.dimension()));
        return List.of(new InsightCandidate(InsightDetector.HEALTH_DIMENSION_CHANGE, ctx.key(),
                change.dimension().name(), null, change.size(), true, now.confidence(), now.partial(), content));
    }

    /** D9: a reserva cobre menos de 3 meses de despesas (dimensão Reserve OK). */
    static List<InsightCandidate> lowReserve(DetectionContext ctx) {
        HealthResult now = ctx.input().health().get(ctx.month());
        if (now == null) {
            return List.of();
        }
        Optional<DimensionResult> reserve = dimension(now, HealthDimension.RESERVE).filter(DimensionResult::isOk);
        Optional<Evidence> coverage = reserve.flatMap(r -> evidence(r, "coverageMonths"));
        Optional<Evidence> balance = reserve.flatMap(r -> evidence(r, "reserveBalance"));
        Optional<Evidence> average = reserve.flatMap(r -> evidence(r, "averageExpense"));
        if (coverage.isEmpty() || balance.isEmpty() || average.isEmpty()
                || coverage.get().value().compareTo(InsightRules.RESERVE_MAX_COVERAGE) >= 0) {
            return List.of();
        }
        BigDecimal c = coverage.get().value();
        Money averageExpense = new Money(average.get().value(), ctx.ledger().currency());
        Money reserveBalance = new Money(balance.get().value(), ctx.ledger().currency());
        Money gap = new Money(average.get().value().multiply(InsightRules.RESERVE_REFERENCE_MONTHS),
                ctx.ledger().currency()).minus(reserveBalance);
        List<YearMonth> windowMonths = List.of(YearMonth.from(average.get().from()),
                YearMonth.from(average.get().to()));
        String band = c.compareTo(BigDecimal.ONE) < 0 ? "<1" : c.compareTo(BigDecimal.TWO) < 0 ? "1-2" : "2-3";

        String title = "Sua reserva cobre cerca de %s meses de despesas.".formatted(InsightTexts.oneDecimal(c));
        String support = "A referência usada no score é 6 meses. Faltam %s (média de %s/mês em %s).".formatted(
                InsightTexts.money(gap), InsightTexts.money(averageExpense), InsightTexts.range(windowMonths));
        List<KeyFigure> figures = List.of(
                new KeyFigure("reserveBalance", "Saldo das contas de reserva", FigureKind.MONEY,
                        reserveBalance.amount(), balance.get().from(), balance.get().to()),
                new KeyFigure("averageExpense", "Média mensal de despesas", FigureKind.MONEY,
                        averageExpense.amount(), average.get().from(), average.get().to()),
                new KeyFigure("coverageMonths", "Meses de cobertura", FigureKind.MONTHS, c, coverage.get().from(),
                        coverage.get().to()),
                new KeyFigure("gap", "Falta para 6 meses", FigureKind.MONEY, gap.amount(), average.get().from(),
                        balance.get().to()));
        String comparison = "Dividimos o saldo das contas de reserva pela média mensal de despesas dos meses fechados "
                + "de %s.".formatted(InsightTexts.range(windowMonths));
        InsightEvidence evidence = new InsightEvidence(figures,
                decimalSeries(ctx, r -> dimension(r, HealthDimension.RESERVE).filter(DimensionResult::isOk)
                        .flatMap(d -> evidence(d, "coverageMonths")).map(Evidence::value).orElse(null)),
                List.of(), comparison, limitations(ctx, now),
                List.of(new SourceRef(InsightSource.HEALTH_SNAPSHOT, now.month().atDay(1), now.asOf()),
                        new SourceRef(InsightSource.OVERVIEW_ACCOUNT_BALANCES, balance.get().from(),
                                balance.get().to())));
        InsightContent content = new InsightContent(title, support, null, evidence,
                List.of(viewHealth(), InsightAction.secondary(ActionKind.VIEW_ACCOUNTS, "Ver contas", Map.of())),
                new InsightRefs(null, null, null, null, HealthDimension.RESERVE));
        return List.of(new InsightCandidate(InsightDetector.LOW_RESERVE, null, band, gap, null,
                c.compareTo(InsightRules.RESERVE_IMPORTANT_COVERAGE) < 0, now.confidence(), now.partial(), content));
    }

    private static InsightAction viewHealth() {
        return InsightAction.primary(ActionKind.VIEW_HEALTH, "Ver saúde", Map.of());
    }

    private static List<Limitation> limitations(DetectionContext ctx, HealthResult now) {
        List<Limitation> limitations = new ArrayList<>();
        if (now.partial()) {
            limitations.add(ctx.partialMonth());
        }
        if (now.estimated()) {
            limitations.add(new Limitation(LimitationCode.ESTIMATED_SCORE,
                    "O score é estimado: há menos de 3 meses fechados de histórico."));
        }
        return limitations;
    }

    private static List<SeriesPoint> series(DetectionContext ctx, Function<HealthResult, Integer> value) {
        return decimalSeries(ctx, r -> {
            Integer v = value.apply(r);
            return v == null ? null : BigDecimal.valueOf(v);
        });
    }

    /** Até 6 meses terminando em P, só os que têm snapshot e valor. */
    private static List<SeriesPoint> decimalSeries(DetectionContext ctx, Function<HealthResult, BigDecimal> value) {
        List<SeriesPoint> series = new ArrayList<>();
        for (YearMonth month : ctx.period().lastMonths(6)) {
            HealthResult result = ctx.input().health().get(month);
            BigDecimal v = result == null ? null : value.apply(result);
            if (v != null) {
                series.add(new SeriesPoint(month, v, result.partial()));
            }
        }
        return series;
    }

    private static Optional<DimensionResult> dimension(HealthResult result, HealthDimension dimension) {
        return result.dimensions().stream().filter(d -> d.dimension() == dimension).findFirst();
    }

    private static Optional<Evidence> evidence(DimensionResult result, String code) {
        return result.evidence().stream().filter(e -> e.code().equals(code)).findFirst();
    }
}
