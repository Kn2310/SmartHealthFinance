package com.smarthealthfinance.forecast.domain.service;

import com.smarthealthfinance.forecast.domain.enums.Confidence;
import com.smarthealthfinance.forecast.domain.enums.Limitation;
import com.smarthealthfinance.forecast.domain.enums.SeriesDirection;
import com.smarthealthfinance.forecast.domain.model.Evidence;
import com.smarthealthfinance.forecast.domain.model.ForecastEntry;
import com.smarthealthfinance.forecast.domain.model.MonthProjection;
import com.smarthealthfinance.forecast.domain.model.MonthProjection.PendingOccurrence;
import com.smarthealthfinance.forecast.domain.model.Projected;
import com.smarthealthfinance.forecast.domain.model.Range;
import com.smarthealthfinance.forecast.domain.model.SeriesAssessment;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.enums.CashFlowRole;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * Projeção do mês e resultado projetado (ADR-0030 §3.1):
 * {@code projetado = realizado (FACT) + recorrentes pendentes (ESTIMATED) + variável restante (ESTIMATED)}.
 * <ul>
 *   <li><b>realizado</b>: despesas de 1 até hoje, com a semântica do ADR-0015 (bruta, cartão na data da compra);</li>
 *   <li><b>recorrentes pendentes</b>: séries elegíveis ainda não realizadas, com data esperada depois de hoje;</li>
 *   <li><b>variável restante</b>: em cada mês de base, a despesa fora das séries elegíveis entre {@code hoje + 1} e o
 *   fim do mês; o valor central é a mediana e a faixa o [mín, máx].</li>
 * </ul>
 * Resultado projetado: {@code receitas realizadas + receitas recorrentes pendentes − despesa projetada}.
 */
public final class MonthProjector {

    private MonthProjector() {
    }

    static MonthProjection project(ProjectionContext ctx) {
        return project(ctx, ctx.baseMonths());
    }

    /**
     * @param baseMonths meses fechados da base do variável restante; vazio = sem estimativa, só os fatos
     */
    static MonthProjection project(ProjectionContext ctx, List<YearMonth> baseMonths) {
        LocalDate today = ctx.today();
        YearMonth month = ctx.month();
        Money zero = ctx.zero();
        boolean partialMonth = !today.equals(month.atEndOfMonth());

        Money realizedExpense = realized(ctx, CashFlowRole.EXPENSE);
        Money realizedIncome = realized(ctx, CashFlowRole.INCOME);

        if (baseMonths.isEmpty()) {
            List<Limitation> limitations = ConfidenceRules.historyLimitations(ctx.historyMonths());
            Projected expense = Projected.fact(realizedExpense, partialMonth, limitations, List.of());
            Projected income = Projected.fact(realizedIncome, partialMonth, limitations, List.of());
            return new MonthProjection(month, today, 0, expense, income, income.minus(expense), List.of());
        }

        List<PendingOccurrence> pendingExpenses = pending(ctx, SeriesDirection.EXPENSE);
        List<PendingOccurrence> pendingIncomes = pending(ctx, SeriesDirection.INCOME);

        var excluded = ctx.eligibleKeys(SeriesDirection.EXPENSE);
        VariableSpending.Estimate variable = VariableSpending.estimate(ctx.input().entries(), baseMonths, zero,
                e -> !excluded.contains(SeriesKeys.keyOf(e)),
                // Só os dias que ainda restam no mês corrente: o dia 31 da base não existe num mês de 30 dias.
                day -> day > today.getDayOfMonth() && day <= month.lengthOfMonth());

        Projected expense = expense(ctx, realizedExpense, pendingExpenses, variable);
        Projected income = income(ctx, realizedIncome, pendingIncomes, partialMonth);
        List<PendingOccurrence> pending = new ArrayList<>(pendingIncomes);
        pending.addAll(pendingExpenses);
        return new MonthProjection(month, today, baseMonths.size(), expense, income, income.minus(expense), pending);
    }

    private static Projected expense(ProjectionContext ctx, Money realized, List<PendingOccurrence> pending,
                                     VariableSpending.Estimate variable) {
        Money central = variable.central();
        Range range = variable.range();
        List<Confidence> parts = new ArrayList<>(List.of(
                ConfidenceRules.sampleConfidence(ctx.historyMonths(), variable.highVariability())));
        List<Evidence> evidence = new ArrayList<>(variable.evidence());
        for (PendingOccurrence item : pending) {
            central = central.plus(item.expected());
            range = range.plus(item.range());
            parts.add(item.confidence());
        }
        evidence.addAll(occurrenceEvidence(ctx, pending));
        return Projected.estimated(realized, central, range,
                ConfidenceRules.finalConfidence(parts, ctx.historyMonths(), ctx.uncategorized()),
                ConfidenceRules.limitations(ctx.historyMonths(), variable.highVariability(), ctx.uncategorized(),
                        false),
                evidence);
    }

    private static Projected income(ProjectionContext ctx, Money realized, List<PendingOccurrence> pending,
                                    boolean partialMonth) {
        List<Limitation> limitations = ConfidenceRules.historyLimitations(ctx.historyMonths());
        if (pending.isEmpty()) {
            return Projected.fact(realized, partialMonth, limitations, List.of());
        }
        Money central = ctx.zero();
        Range range = Range.point(ctx.zero());
        List<Confidence> parts = new ArrayList<>();
        for (PendingOccurrence item : pending) {
            central = central.plus(item.expected());
            range = range.plus(item.range());
            parts.add(item.confidence());
        }
        return Projected.estimated(realized, central, range,
                ConfidenceRules.finalConfidence(parts, ctx.historyMonths(), false), limitations,
                occurrenceEvidence(ctx, pending));
    }

    /** Séries elegíveis do sentido ainda não realizadas no mês, com data esperada depois de hoje. */
    static List<PendingOccurrence> pending(ProjectionContext ctx, SeriesDirection direction) {
        return ctx.eligible(direction).stream()
                .filter(a -> !a.realizedThisMonth() && a.expectedDate().isAfter(ctx.today()))
                .map(MonthProjector::occurrence)
                .toList();
    }

    static PendingOccurrence occurrence(SeriesAssessment assessment) {
        var series = assessment.series();
        return new PendingOccurrence(series.direction(), series.key(), series.label(), assessment.expectedDate(),
                series.effectiveAmount(), series.effectiveRange(), series.effectiveConfidence(), series.channel());
    }

    private static List<Evidence> occurrenceEvidence(ProjectionContext ctx, List<PendingOccurrence> pending) {
        List<Evidence> evidence = new ArrayList<>();
        for (PendingOccurrence item : pending) {
            ctx.assessments().stream()
                    .filter(a -> a.series().direction() == item.direction() && a.series().key().equals(item.key()))
                    .findFirst()
                    .ifPresent(a -> a.series().occurrences().forEach(id -> evidence.add(Evidence.occurrence(id))));
        }
        return evidence;
    }

    private static Money realized(ProjectionContext ctx, CashFlowRole role) {
        YearMonth month = ctx.month();
        return ctx.input().entries().stream()
                .filter(e -> e.role() == role && YearMonth.from(e.day()).equals(month)
                        && !e.day().isAfter(ctx.today()))
                .map(ForecastEntry::amount)
                .reduce(ctx.zero(), Money::plus);
    }
}
