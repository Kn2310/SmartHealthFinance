package com.smarthealthfinance.forecast.domain.service;

import com.smarthealthfinance.forecast.domain.enums.SeriesDirection;
import com.smarthealthfinance.forecast.domain.model.Evidence;
import com.smarthealthfinance.forecast.domain.model.NextIncome;
import com.smarthealthfinance.forecast.domain.model.Projected;
import com.smarthealthfinance.forecast.domain.model.RecurringSeries;
import com.smarthealthfinance.forecast.domain.model.SeriesAssessment;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Próxima receita prevista (ADR-0030 §3.2): a série de receita elegível com a próxima data esperada depois de hoje e
 * ainda não realizada no ciclo; no empate, o maior valor. Sempre {@code ESTIMATED}. Sem série elegível, nada.
 */
final class NextIncomeProjector {

    private NextIncomeProjector() {
    }

    static Optional<NextIncome> project(ProjectionContext ctx) {
        if (!ctx.sufficient()) {
            return Optional.empty();
        }
        return ctx.eligible(SeriesDirection.INCOME).stream()
                .map(a -> new Candidate(a, nextDate(ctx, a)))
                .min(Comparator.comparing(Candidate::date)
                        .thenComparing((Candidate c) -> c.assessment().series().effectiveAmount().amount(),
                                Comparator.reverseOrder())
                        .thenComparing(c -> c.assessment().series().key()))
                .map(candidate -> toNextIncome(ctx, candidate));
    }

    private record Candidate(SeriesAssessment assessment, LocalDate date) {}

    /** Neste mês, se ainda vem depois de hoje e não foi realizada; senão, no mês seguinte. */
    private static LocalDate nextDate(ProjectionContext ctx, SeriesAssessment assessment) {
        if (!assessment.realizedThisMonth() && assessment.expectedDate().isAfter(ctx.today())) {
            return assessment.expectedDate();
        }
        return assessment.series().expectedDateIn(ctx.month().plusMonths(1));
    }

    private static NextIncome toNextIncome(ProjectionContext ctx, Candidate candidate) {
        RecurringSeries series = candidate.assessment().series();
        List<Evidence> evidence = series.occurrences().stream().map(Evidence::occurrence).toList();
        Projected amount = Projected.estimated(ctx.zero(), series.effectiveAmount(), series.effectiveRange(),
                ConfidenceRules.finalConfidence(List.of(series.effectiveConfidence()), ctx.historyMonths(), false),
                ConfidenceRules.historyLimitations(ctx.historyMonths()), evidence);
        return new NextIncome(series.key(), series.label(), candidate.date(), series.channel(), amount);
    }
}
