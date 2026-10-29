package com.smarthealthfinance.forecast.domain.service;

import com.smarthealthfinance.forecast.domain.enums.OutcomePhase;
import com.smarthealthfinance.forecast.domain.model.ForecastEntry;
import com.smarthealthfinance.forecast.domain.model.MonthOutcome;
import com.smarthealthfinance.forecast.domain.model.MonthOutcome.Error;
import com.smarthealthfinance.forecast.domain.model.MonthOutcome.Figures;
import com.smarthealthfinance.forecast.domain.model.MonthProjection;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.enums.CashFlowRole;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/**
 * Previsto × realizado (ADR-0030 §6, ADR-0031 §6): funções puras para congelar a projeção do mês que fechou, medir o
 * realizado e calcular o erro. A gravação (idempotente, {@code ON CONFLICT DO NOTHING}) é da camada de aplicação.
 * <ul>
 *   <li>o mês fecha na virada: o snapshot anterior, de um mês anterior ao corrente, vira o congelado;</li>
 *   <li>até o dia {@value #SETTLE_DAY} do mês seguinte o realizado é regravado (importações atrasadas); depois, fica
 *   fixo;</li>
 *   <li>erro = {@code realizado − previsto}, e em % do previsto (2 casas, HALF_EVEN).</li>
 * </ul>
 */
public final class MonthOutcomes {

    /** Último dia do mês seguinte em que o realizado ainda é reconciliado. */
    public static final int SETTLE_DAY = 5;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private MonthOutcomes() {
    }

    /** O mês cuja projeção deve ser congelada: o do snapshot anterior, se for anterior ao mês de {@code today}. */
    public static Optional<YearMonth> monthToFreeze(LocalDate previousAsOf, LocalDate today) {
        YearMonth previous = YearMonth.from(previousAsOf);
        return previous.isBefore(YearMonth.from(today)) ? Optional.of(previous) : Optional.empty();
    }

    public static OutcomePhase phase(YearMonth month, LocalDate today) {
        if (!today.isAfter(month.atEndOfMonth())) {
            return OutcomePhase.CURRENT;
        }
        return today.isAfter(month.plusMonths(1).atDay(SETTLE_DAY)) ? OutcomePhase.SETTLED : OutcomePhase.RECONCILING;
    }

    /** Congela a projeção do mês (valor central e faixa), ainda sem realizado. */
    public static MonthOutcome freeze(MonthProjection projection, String algorithmVersion) {
        return new MonthOutcome(projection.month(), algorithmVersion,
                new Figures(projection.expense().value(), projection.income().value(), projection.net().value()),
                new Figures(projection.expense().low(), projection.income().low(), projection.net().low()),
                new Figures(projection.expense().high(), projection.income().high(), projection.net().high()),
                projection.expense().confidence().min(projection.income().confidence()), null, null);
    }

    /** O realizado do mês inteiro: despesa e receita lançadas no mês. */
    public static Figures realized(List<ForecastEntry> entries, YearMonth month, Money zero) {
        Money expense = sum(entries, month, CashFlowRole.EXPENSE, zero);
        Money income = sum(entries, month, CashFlowRole.INCOME, zero);
        return new Figures(expense, income, income.minus(expense));
    }

    /** Regrava só o realizado e o erro; a projeção congelada nunca é reescrita. */
    public static MonthOutcome reconcile(MonthOutcome frozen, Figures realized) {
        return new MonthOutcome(frozen.month(), frozen.algorithmVersion(), frozen.projected(), frozen.projectedLow(),
                frozen.projectedHigh(), frozen.confidence(), realized, error(frozen, realized));
    }

    static Error error(MonthOutcome frozen, Figures realized) {
        Figures projected = frozen.projected();
        Figures absolute = new Figures(realized.expense().minus(projected.expense()),
                realized.income().minus(projected.income()), realized.net().minus(projected.net()));
        return new Error(absolute, percent(absolute.expense(), projected.expense()),
                percent(absolute.income(), projected.income()),
                within(realized.expense(), frozen.projectedLow().expense(), frozen.projectedHigh().expense()),
                within(realized.income(), frozen.projectedLow().income(), frozen.projectedHigh().income()));
    }

    /** |erro| ÷ |previsto| em %, 2 casas HALF_EVEN; nulo se o previsto é zero. */
    static BigDecimal percent(Money error, Money projected) {
        if (projected.amount().signum() == 0) {
            return null;
        }
        return error.amount().abs().multiply(HUNDRED)
                .divide(projected.amount().abs(), 2, RoundingMode.HALF_EVEN);
    }

    private static boolean within(Money value, Money min, Money max) {
        return value.amount().compareTo(min.amount()) >= 0 && value.amount().compareTo(max.amount()) <= 0;
    }

    private static Money sum(List<ForecastEntry> entries, YearMonth month, CashFlowRole role, Money zero) {
        return entries.stream()
                .filter(e -> e.role() == role && YearMonth.from(e.day()).equals(month))
                .map(ForecastEntry::amount)
                .reduce(zero, Money::plus);
    }
}
