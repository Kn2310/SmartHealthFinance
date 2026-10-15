package com.smarthealthfinance.health.domain.service;

import com.smarthealthfinance.analytics.domain.service.FlowLedger;
import com.smarthealthfinance.analytics.domain.valueobject.AnalyticsPeriod;
import com.smarthealthfinance.health.domain.enums.EvidenceKind;
import com.smarthealthfinance.health.domain.enums.EvidenceSource;
import com.smarthealthfinance.health.domain.enums.HealthDimension;
import com.smarthealthfinance.health.domain.enums.InsufficientReason;
import com.smarthealthfinance.health.domain.model.DimensionResult;
import com.smarthealthfinance.health.domain.model.Evidence;
import com.smarthealthfinance.health.domain.model.HealthInput;
import com.smarthealthfinance.health.domain.model.HealthWindow;
import com.smarthealthfinance.health.domain.valueobject.ScoreCurve;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.enums.CashFlowRole;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * Cash Flow (ADR-0017 §2), mínimo n ≥ 1: taxa de sobra r = Σ resultado ÷ Σ receitas em W e p = meses com
 * resultado ≥ 0 ÷ n. Nota = 0,7 · S(r) + 0,3 · 100 · p. Sem receita com despesa, S(r) = 0.
 */
final class CashFlowDimension {

    static final ScoreCurve CURVE = ScoreCurve.of("-0.10", "0", "0", "30", "0.10", "60", "0.20", "80", "0.40", "100");

    private static final BigDecimal ATTENTION_RATE = new BigDecimal("0.10");

    /** @param lastNegativeMonth mês mais recente de W com resultado negativo, para os próximos passos */
    record Assessment(DimensionResult result, YearMonth lastNegativeMonth, Money lastNegativeNet) {}

    private CashFlowDimension() {
    }

    static Assessment assess(HealthInput input, HealthWindow window) {
        if (window.isEmpty()) {
            return new Assessment(DimensionResult.insufficient(HealthDimension.CASH_FLOW,
                    InsufficientReason.NO_CLOSED_MONTH), null, null);
        }
        FlowLedger ledger = input.ledger();
        AnalyticsPeriod range = window.range();
        Money income = ledger.total(CashFlowRole.INCOME, range);
        Money expense = ledger.total(CashFlowRole.EXPENSE, range);
        Money refunds = ledger.total(CashFlowRole.REFUND, range);
        if (income.amount().signum() == 0 && expense.amount().signum() == 0) {
            return new Assessment(DimensionResult.insufficient(HealthDimension.CASH_FLOW,
                    InsufficientReason.NO_CASH_FLOW), null, null);
        }
        Money net = ledger.net(range);

        int positive = 0;
        YearMonth lastNegative = null;
        Money lastNegativeNet = null;
        for (YearMonth month : window.months()) {
            Money monthNet = ledger.net(AnalyticsPeriod.of(month));
            if (monthNet.amount().signum() >= 0) {
                positive++;
            }
            else {
                lastNegative = month;
                lastNegativeNet = monthNet;
            }
        }
        int n = window.size();

        BigDecimal rate = income.amount().signum() == 0 ? null : Ratios.divide(net.amount(), income.amount());
        BigDecimal rateScore = rate == null ? BigDecimal.ZERO : CURVE.score(rate);
        BigDecimal positiveShare = Ratios.divide(positive, n);
        int score = Ratios.toScore(Ratios.weighted("0.7", rateScore)
                .add(Ratios.weighted("0.3", positiveShare.multiply(BigDecimal.valueOf(100)))));
        Money averageNet = ledger.divide(net, n);

        boolean attention = positive < n || rate == null || rate.compareTo(ATTENTION_RATE) < 0;
        String explanation = "Receitas e reembolsos cobriram as despesas em %d de %d %s (%s). %s".formatted(
                positive, n, n == 1 ? "mês" : "meses", HealthTexts.months(window.months()),
                rate == null ? "Não houve receitas no período."
                        : averageNet.amount().signum() >= 0
                                ? "Sobra média de %s/mês (%s da receita).".formatted(HealthTexts.money(averageNet),
                                        HealthTexts.percent(HealthTexts.toPercent(rate)))
                                : "Déficit médio de %s/mês (%s da receita).".formatted(
                                        HealthTexts.money(averageNet.negate()),
                                        HealthTexts.percent(HealthTexts.toPercent(rate))));

        List<Evidence> evidence = new ArrayList<>();
        evidence.add(money("income", "Receitas", income, range));
        evidence.add(money("refunds", "Reembolsos", refunds, range));
        evidence.add(money("expense", "Despesas", expense, range));
        evidence.add(money("averageNet", "Resultado médio por mês", averageNet, range));
        evidence.add(new Evidence("positiveMonths", "Meses com resultado ≥ 0", EvidenceKind.COUNT,
                BigDecimal.valueOf(positive), range.from(), range.to(), EvidenceSource.ANALYTICS_MONTHLY));
        evidence.add(new Evidence("months", "Meses fechados", EvidenceKind.COUNT, BigDecimal.valueOf(n),
                range.from(), range.to(), EvidenceSource.ANALYTICS_MONTHLY));
        if (rate != null) {
            evidence.add(new Evidence("savingsRate", "Sobra em relação à receita", EvidenceKind.PERCENT,
                    HealthTexts.toPercent(rate).setScale(2), range.from(), range.to(),
                    EvidenceSource.ANALYTICS_MONTHLY));
        }
        return new Assessment(DimensionResult.ok(HealthDimension.CASH_FLOW, score, explanation, attention, evidence),
                lastNegative, lastNegativeNet);
    }

    private static Evidence money(String code, String label, Money value, AnalyticsPeriod range) {
        return new Evidence(code, label, EvidenceKind.MONEY, value.amount(), range.from(), range.to(),
                EvidenceSource.ANALYTICS_MONTHLY);
    }
}
