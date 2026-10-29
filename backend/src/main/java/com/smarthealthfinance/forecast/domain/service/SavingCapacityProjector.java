package com.smarthealthfinance.forecast.domain.service;

import com.smarthealthfinance.cards.domain.enums.InvoiceItemKind;
import com.smarthealthfinance.cards.domain.enums.InvoiceStatus;
import com.smarthealthfinance.cards.domain.valueobject.CardPosition;
import com.smarthealthfinance.cards.domain.valueobject.CardPosition.InvoiceLine;
import com.smarthealthfinance.cards.domain.valueobject.InvoiceStatement;
import com.smarthealthfinance.forecast.domain.enums.Confidence;
import com.smarthealthfinance.forecast.domain.enums.SeriesDirection;
import com.smarthealthfinance.forecast.domain.model.Evidence;
import com.smarthealthfinance.forecast.domain.model.Projected;
import com.smarthealthfinance.forecast.domain.model.Range;
import com.smarthealthfinance.forecast.domain.model.RecurringSeries;
import com.smarthealthfinance.forecast.domain.model.SavingCapacity;
import com.smarthealthfinance.forecast.domain.model.SavingCapacity.MonthlyCapacity;
import com.smarthealthfinance.forecast.domain.model.SeriesAssessment;
import com.smarthealthfinance.shared.domain.Money;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Capacidade de aporte mensal (ADR-0030 §7): em cada mês do horizonte (o corrente e os 3 seguintes),
 * {@code receitas recorrentes − despesas recorrentes − gasto variável − parcelas comprometidas}; o valor é a mediana
 * desses meses e a faixa vai do menor limite baixo ao maior limite alto. Sempre {@code ESTIMATED}.
 * <ul>
 *   <li>receitas e despesas recorrentes: as séries elegíveis (valor esperado, faixa [mín, máx]);</li>
 *   <li>gasto variável: a despesa fora das séries e <b>fora de compras parceladas</b> (as parcelas entram como
 *   comprometidas, para não contar duas vezes), mediana e [mín, máx] dos meses de base;</li>
 *   <li>parcelas comprometidas (fato): nas faturas que vencem no mês, as linhas de parcela; nas que ainda não
 *   começaram, o valor delas.</li>
 * </ul>
 */
final class SavingCapacityProjector {

    private SavingCapacityProjector() {
    }

    static Optional<SavingCapacity> project(ProjectionContext ctx) {
        if (!ctx.sufficient()) {
            return Optional.empty();
        }
        Money zero = ctx.zero();
        List<Confidence> parts = new ArrayList<>();
        Set<Evidence> evidence = new LinkedHashSet<>();

        Totals income = totals(ctx, SeriesDirection.INCOME, parts, evidence);
        Totals expense = totals(ctx, SeriesDirection.EXPENSE, parts, evidence);

        Set<String> excluded = ctx.eligibleKeys(SeriesDirection.EXPENSE);
        VariableSpending.Estimate variable = VariableSpending.estimate(ctx.input().entries(), ctx.baseMonths(), zero,
                e -> !e.installment() && !excluded.contains(SeriesKeys.keyOf(e)), day -> true);
        parts.add(ConfidenceRules.sampleConfidence(ctx.historyMonths(), variable.highVariability()));
        evidence.addAll(variable.evidence());

        List<MonthlyCapacity> months = new ArrayList<>();
        for (int i = 0; i <= CashFlowProjector.HORIZON_MONTHS; i++) {
            YearMonth month = ctx.month().plusMonths(i);
            Money committed = committed(ctx, month);
            Money central = income.central().minus(expense.central()).minus(variable.central()).minus(committed);
            Money low = income.range().min().minus(expense.range().max()).minus(variable.range().max())
                    .minus(committed);
            Money high = income.range().max().minus(expense.range().min()).minus(variable.range().min())
                    .minus(committed);
            months.add(new MonthlyCapacity(month, central, low, high, committed));
        }

        Money central = Statistics.median(months.stream().map(MonthlyCapacity::central).toList());
        Money fact = Statistics.median(months.stream().map(MonthlyCapacity::committedInstallments).toList()).negate();
        Money low = months.stream().map(MonthlyCapacity::low).min(Comparator.comparing(Money::amount)).orElseThrow();
        Money high = months.stream().map(MonthlyCapacity::high).max(Comparator.comparing(Money::amount)).orElseThrow();

        Projected monthly = Projected.estimated(fact, central.minus(fact), new Range(low.minus(fact), high.minus(fact)),
                ConfidenceRules.finalConfidence(parts, ctx.historyMonths(), ctx.uncategorized()),
                ConfidenceRules.limitations(ctx.historyMonths(), variable.highVariability(), ctx.uncategorized(),
                        false),
                new ArrayList<>(evidence));
        return Optional.of(new SavingCapacity(months, monthly));
    }

    private record Totals(Money central, Range range) {}

    private static Totals totals(ProjectionContext ctx, SeriesDirection direction, List<Confidence> parts,
                                 Set<Evidence> evidence) {
        Money central = ctx.zero();
        Range range = Range.point(ctx.zero());
        for (SeriesAssessment assessment : ctx.eligible(direction)) {
            RecurringSeries series = assessment.series();
            central = central.plus(series.effectiveAmount());
            range = range.plus(series.effectiveRange());
            parts.add(series.effectiveConfidence());
            series.occurrences().forEach(id -> evidence.add(Evidence.occurrence(id)));
        }
        return new Totals(central, range);
    }

    /** Parcelas em faturas que vencem no mês, de todos os cartões. */
    static Money committed(ProjectionContext ctx, YearMonth dueMonth) {
        Money total = ctx.zero();
        for (CardPosition card : ctx.input().cards()) {
            for (InvoiceStatement statement : card.statements()) {
                if (!statement.dates().dueMonth().equals(dueMonth)) {
                    continue;
                }
                if (statement.status() == InvoiceStatus.FUTURE) {
                    total = total.plus(Statistics.atLeastZero(statement.amount()));
                    continue;
                }
                for (InvoiceLine line : card.lines().getOrDefault(statement.dates().closingMonth(), List.of())) {
                    if (line.kind() == InvoiceItemKind.CHARGE && CardInvoiceProjector.isInstallment(line)) {
                        total = total.plus(line.net());
                    }
                }
            }
        }
        return total;
    }
}
