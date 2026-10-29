package com.smarthealthfinance.forecast.domain.service;

import com.smarthealthfinance.forecast.domain.enums.ChannelKind;
import com.smarthealthfinance.forecast.domain.enums.Confidence;
import com.smarthealthfinance.forecast.domain.enums.Limitation;
import com.smarthealthfinance.forecast.domain.enums.SeriesDirection;
import com.smarthealthfinance.forecast.domain.model.CashFlowProjection;
import com.smarthealthfinance.forecast.domain.model.CashFlowProjection.BalancePoint;
import com.smarthealthfinance.forecast.domain.model.CashFlowProjection.CashFlowMonth;
import com.smarthealthfinance.forecast.domain.model.CashFlowProjection.Trough;
import com.smarthealthfinance.forecast.domain.model.Evidence;
import com.smarthealthfinance.forecast.domain.model.ForecastEntry;
import com.smarthealthfinance.forecast.domain.model.Projected;
import com.smarthealthfinance.forecast.domain.model.Range;
import com.smarthealthfinance.forecast.domain.model.RecurringSeries;
import com.smarthealthfinance.forecast.domain.model.SeriesAssessment;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.enums.CashFlowRole;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Fluxo de caixa projetado (ADR-0030 §3.4), lente de caixa, só o saldo total.
 * <ul>
 *   <li>ponto de partida: o saldo total de hoje (fato);</li>
 *   <li>movimentos por dia, depois de hoje: séries elegíveis <b>em conta</b> (as de cartão já estão no padrão da
 *   fatura); pagamento das faturas no vencimento (o faturado e as parcelas são fato, as novas compras estimativa);
 *   gasto variável em conta (despesa fora do cartão e fora das séries), a mediana mensal distribuída pela curva
 *   diária média dos meses de base;</li>
 *   <li>horizonte: até o fim do mês corrente + {@value #HORIZON_MONTHS} meses;</li>
 *   <li>faixa: entradas somam o mínimo e saídas o máximo no limite de baixo (e o inverso no de cima), acumulada dia a
 *   dia, logo mês a mês (ADR-0030 §4).</li>
 * </ul>
 */
final class CashFlowProjector {

    static final int HORIZON_MONTHS = 3;
    private static final int CURVE_SCALE = 10;

    private CashFlowProjector() {
    }

    static LocalDate horizonEnd(LocalDate today) {
        return YearMonth.from(today).plusMonths(HORIZON_MONTHS).atEndOfMonth();
    }

    /** Um movimento: entrada ou saída, a parte de fato e a estimada (central, mínimo e máximo). */
    private record Movement(boolean income, Money fact, Money central, Money min, Money max) {}

    static Optional<CashFlowProjection> project(ProjectionContext ctx, CardInvoiceProjector.Result invoices) {
        if (!ctx.sufficient()) {
            return Optional.empty();
        }
        LocalDate today = ctx.today();
        LocalDate end = horizonEnd(today);
        Money zero = ctx.zero();
        TreeMap<LocalDate, List<Movement>> movements = new TreeMap<>();
        List<Confidence> parts = new ArrayList<>();
        Set<Evidence> evidence = new LinkedHashSet<>();

        // 1. Séries elegíveis em conta.
        for (SeriesAssessment assessment : ctx.assessments()) {
            RecurringSeries series = assessment.series();
            if (!assessment.isEligible() || series.channel().kind() != ChannelKind.ACCOUNT) {
                continue;
            }
            for (YearMonth month = ctx.month(); !month.isAfter(YearMonth.from(end)); month = month.plusMonths(1)) {
                LocalDate date = series.expectedDateIn(month);
                if (!date.isAfter(today) || (month.equals(ctx.month()) && assessment.realizedThisMonth())) {
                    continue;
                }
                Range range = series.effectiveRange();
                movements.computeIfAbsent(date, d -> new ArrayList<>()).add(new Movement(
                        series.direction() == SeriesDirection.INCOME, zero, series.effectiveAmount(), range.min(),
                        range.max()));
                parts.add(series.effectiveConfidence());
                series.occurrences().forEach(id -> evidence.add(Evidence.occurrence(id)));
            }
        }

        // 2. Pagamento de faturas.
        boolean partialCardHistory = false;
        boolean cardVariability = false;
        for (CardInvoiceProjector.InvoicePayment payment : invoices.payments()) {
            Projected amount = payment.amount();
            movements.computeIfAbsent(payment.date(), d -> new ArrayList<>()).add(new Movement(false,
                    amount.factPart(), amount.estimatedPart(), amount.low().minus(amount.factPart()),
                    amount.high().minus(amount.factPart())));
            if (amount.isEstimated()) {
                parts.add(amount.confidence());
                evidence.addAll(amount.evidence());
            }
            partialCardHistory |= amount.limitations().contains(Limitation.PARTIAL_CARD_HISTORY);
            cardVariability |= amount.limitations().contains(Limitation.HIGH_VARIABILITY);
        }

        // 3. Gasto variável em conta.
        Set<String> excluded = ctx.eligibleKeys(SeriesDirection.EXPENSE);
        VariableSpending.Estimate variable = VariableSpending.estimate(ctx.input().entries(), ctx.baseMonths(), zero,
                e -> e.channel().kind() == ChannelKind.ACCOUNT && !excluded.contains(SeriesKeys.keyOf(e)),
                day -> true);
        addVariable(ctx, variable, end, movements);
        parts.add(ConfidenceRules.sampleConfidence(ctx.historyMonths(), variable.highVariability()));
        evidence.addAll(variable.evidence());

        // Simulação diária: três trajetórias (central, baixa, alta) e a parte de fato.
        boolean variability = variable.highVariability() || cardVariability;
        List<Limitation> limitations = ConfidenceRules.limitations(ctx.historyMonths(), variability,
                ctx.uncategorized(), partialCardHistory);
        Confidence confidence = ConfidenceRules.finalConfidence(parts, ctx.historyMonths(), ctx.uncategorized());

        Money balance = ctx.input().totalBalance();
        Money fact = balance;
        Money low = balance;
        Money high = balance;
        Money central = balance;
        List<CashFlowMonth> months = new ArrayList<>();
        List<BalancePoint> points = new ArrayList<>();

        for (YearMonth month = ctx.month(); !month.isAfter(YearMonth.from(end)); month = month.plusMonths(1)) {
            Money opening = central;
            Money inflowFact = zero;
            Money inflowEstimated = zero;
            Money outflowFact = zero;
            Money outflowEstimated = zero;
            LocalDate first = month.equals(ctx.month()) ? today.plusDays(1) : month.atDay(1);
            for (LocalDate day = first; !day.isAfter(month.atEndOfMonth()); day = day.plusDays(1)) {
                for (Movement movement : movements.getOrDefault(day, List.of())) {
                    if (movement.income()) {
                        fact = fact.plus(movement.fact());
                        central = central.plus(movement.fact()).plus(movement.central());
                        low = low.plus(movement.fact()).plus(movement.min());
                        high = high.plus(movement.fact()).plus(movement.max());
                        inflowFact = inflowFact.plus(movement.fact());
                        inflowEstimated = inflowEstimated.plus(movement.central());
                    }
                    else {
                        fact = fact.minus(movement.fact());
                        central = central.minus(movement.fact()).minus(movement.central());
                        low = low.minus(movement.fact()).minus(movement.max());
                        high = high.minus(movement.fact()).minus(movement.min());
                        outflowFact = outflowFact.plus(movement.fact());
                        outflowEstimated = outflowEstimated.plus(movement.central());
                    }
                }
                points.add(new BalancePoint(day, central, low, high));
            }
            Projected closing = Projected.estimated(fact, central.minus(fact),
                    new Range(low.minus(fact), high.minus(fact)), confidence, limitations, List.of());
            months.add(new CashFlowMonth(month, opening, inflowFact, inflowEstimated, outflowFact, outflowEstimated,
                    closing));
        }

        LocalDate next30 = today.plusDays(UpcomingEventsProjector.WINDOW_DAYS);
        Trough trough = trough(points, today.plusDays(1), end);
        Trough troughNext30 = trough(points, today.plusDays(1), next30);
        return Optional.of(new CashFlowProjection(today, end, months, trough, troughNext30, confidence, limitations,
                new ArrayList<>(evidence)));
    }

    private static Trough trough(List<BalancePoint> points, LocalDate from, LocalDate to) {
        List<BalancePoint> window = points.stream()
                .filter(p -> !p.date().isBefore(from) && !p.date().isAfter(to)).toList();
        BalancePoint lowestCentral = window.getFirst();
        BalancePoint lowestLow = window.getFirst();
        for (BalancePoint point : window) {
            if (point.central().amount().compareTo(lowestCentral.central().amount()) < 0) {
                lowestCentral = point;
            }
            if (point.low().amount().compareTo(lowestLow.low().amount()) < 0) {
                lowestLow = point;
            }
        }
        return new Trough(lowestCentral, lowestLow);
    }

    // ---- gasto variável distribuído pela curva diária média ----

    private static void addVariable(ProjectionContext ctx, VariableSpending.Estimate variable, LocalDate end,
                                    TreeMap<LocalDate, List<Movement>> movements) {
        if (variable.range().max().amount().signum() == 0) {
            return;
        }
        BigDecimal[] weights = dailyWeights(ctx, variable.months());
        Money zero = ctx.zero();
        for (YearMonth month = ctx.month(); !month.isAfter(YearMonth.from(end)); month = month.plusMonths(1)) {
            int length = month.lengthOfMonth();
            int firstDay = month.equals(ctx.month()) ? ctx.today().getDayOfMonth() + 1 : 1;
            if (firstDay > length) {
                continue;
            }
            BigDecimal total = BigDecimal.ZERO;
            for (int d = 1; d <= length; d++) {
                total = total.add(weights[d]);
            }
            if (total.signum() == 0) {
                continue;
            }
            Money[] central = distribute(variable.central(), weights, total, firstDay, length);
            Money[] min = distribute(variable.range().min(), weights, total, firstDay, length);
            Money[] max = distribute(variable.range().max(), weights, total, firstDay, length);
            for (int d = firstDay; d <= length; d++) {
                int i = d - firstDay;
                movements.computeIfAbsent(month.atDay(d), k -> new ArrayList<>())
                        .add(new Movement(false, zero, central[i], min[i], max[i]));
            }
        }
    }

    /**
     * Distribui {@code monthly} nos dias {@code firstDay..length} na proporção dos pesos: o total do trecho é
     * arredondado (2 casas, HALF_EVEN) e o último dia absorve a diferença, para a soma ser exata.
     */
    private static Money[] distribute(Money monthly, BigDecimal[] weights, BigDecimal total, int firstDay,
                                      int length) {
        BigDecimal included = BigDecimal.ZERO;
        for (int d = firstDay; d <= length; d++) {
            included = included.add(weights[d]);
        }
        BigDecimal portion = monthly.amount().multiply(included).divide(total, Statistics.SCALE, RoundingMode.HALF_EVEN);
        Money[] days = new Money[length - firstDay + 1];
        BigDecimal assigned = BigDecimal.ZERO;
        for (int d = firstDay; d <= length; d++) {
            BigDecimal value = d == length ? portion.subtract(assigned)
                    : monthly.amount().multiply(weights[d]).divide(total, Statistics.SCALE, RoundingMode.HALF_EVEN);
            days[d - firstDay] = new Money(value, monthly.currency());
            assigned = assigned.add(value);
        }
        return days;
    }

    /**
     * Peso médio de cada dia do mês (1–31) nos meses de base: a fatia do gasto do mês que cai no dia, média dos meses
     * que têm o dia e algum gasto. Determinístico; é normalizado por mês na distribuição.
     */
    private static BigDecimal[] dailyWeights(ProjectionContext ctx, List<YearMonth> months) {
        Set<String> excluded = ctx.eligibleKeys(SeriesDirection.EXPENSE);
        BigDecimal[] sum = new BigDecimal[32];
        int[] count = new int[32];
        java.util.Arrays.fill(sum, BigDecimal.ZERO);
        for (YearMonth month : months) {
            BigDecimal[] byDay = new BigDecimal[32];
            java.util.Arrays.fill(byDay, BigDecimal.ZERO);
            BigDecimal total = BigDecimal.ZERO;
            for (ForecastEntry e : ctx.input().entries()) {
                if (e.role() == CashFlowRole.EXPENSE && YearMonth.from(e.day()).equals(month)
                        && e.channel().kind() == ChannelKind.ACCOUNT
                        && !excluded.contains(SeriesKeys.keyOf(e))) {
                    byDay[e.day().getDayOfMonth()] = byDay[e.day().getDayOfMonth()].add(e.amount().amount());
                    total = total.add(e.amount().amount());
                }
            }
            if (total.signum() == 0) {
                continue;
            }
            for (int d = 1; d <= month.lengthOfMonth(); d++) {
                sum[d] = sum[d].add(byDay[d].divide(total, CURVE_SCALE, RoundingMode.HALF_EVEN));
                count[d]++;
            }
        }
        BigDecimal[] weights = new BigDecimal[32];
        for (int d = 0; d <= 31; d++) {
            weights[d] = count[d] == 0 ? BigDecimal.ZERO
                    : sum[d].divide(BigDecimal.valueOf(count[d]), CURVE_SCALE, RoundingMode.HALF_EVEN);
        }
        return weights;
    }

}
