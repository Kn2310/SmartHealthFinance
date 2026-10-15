package com.smarthealthfinance.health.domain.service;

import com.smarthealthfinance.analytics.domain.service.FlowLedger;
import com.smarthealthfinance.analytics.domain.valueobject.AnalyticsPeriod;
import com.smarthealthfinance.health.domain.enums.Confidence;
import com.smarthealthfinance.health.domain.enums.HealthDimension;
import com.smarthealthfinance.health.domain.enums.HealthState;
import com.smarthealthfinance.health.domain.model.DimensionResult;
import com.smarthealthfinance.health.domain.model.HealthInput;
import com.smarthealthfinance.health.domain.model.HealthResult;
import com.smarthealthfinance.health.domain.model.HealthWindow;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.enums.CashFlowRole;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

/**
 * Cálculo do Financial Health {@value #ALGORITHM_VERSION} (ADR-0017): puro e determinístico, sem I/O. A entrada já
 * traz as linhas do fluxo de caixa e os saldos lidos das portas de Analytics e Overview (ADR-0018 §4).
 * <p>
 * Score geral = Σ (peso × nota) ÷ Σ (pesos das dimensões OK), sobre as notas inteiras exibidas, arredondado para
 * inteiro (HALF_EVEN). Exige Cash Flow OK e mais uma dimensão OK.
 */
public final class HealthCalculator {

    /** Mudou curva, peso, limiar, entrada ou regra de status → nova versão (ADR-0017 §5). */
    public static final String ALGORITHM_VERSION = "health-v1";

    public static final int ESTIMATED_BELOW_MONTHS = 3;
    static final BigDecimal HIGH_CONFIDENCE_UNCATEGORIZED = BigDecimal.valueOf(10);
    static final BigDecimal MEDIUM_CONFIDENCE_UNCATEGORIZED = BigDecimal.valueOf(25);
    static final int HIGH_CONFIDENCE_MONTHS = 6;
    static final int MEDIUM_CONFIDENCE_MONTHS = 3;

    private HealthCalculator() {
    }

    public static HealthResult calculate(HealthInput input) {
        Objects.requireNonNull(input, "input");
        HealthWindow window = HealthWindow.of(input.period(), input.firstActivity());

        CashFlowDimension.Assessment cashFlow = CashFlowDimension.assess(input, window);
        SpendingDimension.Assessment spending = SpendingDimension.assess(input);
        ReserveDimension.Assessment reserve = ReserveDimension.assess(input, window);
        DimensionResult consistency = ConsistencyDimension.assess(input, window);

        List<DimensionResult> dimensions = List.of(cashFlow.result(), spending.result(),
                DimensionResult.notApplicable(HealthDimension.CREDIT), DimensionResult.notApplicable(HealthDimension.DEBT),
                reserve.result(), consistency);

        List<DimensionResult> available = dimensions.stream().filter(DimensionResult::isOk).toList();
        int weight = available.stream().mapToInt(d -> d.dimension().weight()).sum();
        HealthResult.Coverage coverage = new HealthResult.Coverage(available.size(), dimensions.size(), weight);

        boolean ready = cashFlow.result().isOk() && available.size() >= 2;
        Integer score = ready ? overall(available, weight) : null;

        int n = window.size();
        BigDecimal uncategorized = uncategorizedShare(input.ledger(), window);
        Confidence confidence = confidence(n, uncategorized);

        return new HealthResult(ALGORITHM_VERSION, input.month(), input.asOf(), input.period().partial(),
                window.isEmpty() ? null : window.range().from(), window.isEmpty() ? null : window.range().to(), n,
                ready ? HealthState.READY : HealthState.INSUFFICIENT_DATA, score, null,
                n < ESTIMATED_BELOW_MONTHS, confidence, uncategorized, coverage, input.activeAccounts(),
                ready ? HealthNarrative.summary(available, coverage) : null, dimensions,
                HealthNarrative.factors(available),
                HealthNarrative.nextSteps(input, window, cashFlow, spending, reserve, uncategorized));
    }

    /** Variação contra o mês anterior, com a mesma versão; nula sem os dois scores. */
    public static HealthResult withVariation(HealthResult current, HealthResult previous) {
        if (current.score() == null || previous == null || previous.score() == null
                || !previous.algorithmVersion().equals(current.algorithmVersion())
                || !previous.month().equals(current.month().minusMonths(1))) {
            return current.withVariation(null);
        }
        return current.withVariation(current.score() - previous.score());
    }

    static int overall(List<DimensionResult> available, int weight) {
        BigDecimal sum = BigDecimal.ZERO;
        for (DimensionResult dimension : available) {
            sum = sum.add(BigDecimal.valueOf((long) dimension.dimension().weight() * dimension.score()));
        }
        // Direto para inteiro: arredondar antes para 4 casas poderia arredondar duas vezes (85,49996 → 85,5 → 86).
        return Ratios.toScore(sum.divide(BigDecimal.valueOf(weight), 0, RoundingMode.HALF_EVEN));
    }

    static Confidence confidence(int months, BigDecimal uncategorizedPercent) {
        if (months >= HIGH_CONFIDENCE_MONTHS && uncategorizedPercent.compareTo(HIGH_CONFIDENCE_UNCATEGORIZED) <= 0) {
            return Confidence.HIGH;
        }
        if (months >= MEDIUM_CONFIDENCE_MONTHS
                && uncategorizedPercent.compareTo(MEDIUM_CONFIDENCE_UNCATEGORIZED) <= 0) {
            return Confidence.MEDIUM;
        }
        return Confidence.LOW;
    }

    /** % do valor gasto em W sem categoria, 1 casa; zero sem gasto ou sem janela. */
    static BigDecimal uncategorizedShare(FlowLedger ledger, HealthWindow window) {
        if (window.isEmpty()) {
            return BigDecimal.ZERO.setScale(1);
        }
        AnalyticsPeriod range = window.range();
        Money expense = ledger.total(CashFlowRole.EXPENSE, range);
        Money uncategorized = ledger.byCategory(CashFlowRole.EXPENSE, range).getOrDefault(null, ledger.zero());
        BigDecimal share = FlowLedger.share(uncategorized, expense);
        return share == null ? BigDecimal.ZERO.setScale(1) : share;
    }
}
