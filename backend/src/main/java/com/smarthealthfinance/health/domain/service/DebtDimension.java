package com.smarthealthfinance.health.domain.service;

import com.smarthealthfinance.analytics.domain.service.FlowLedger;
import com.smarthealthfinance.analytics.domain.valueobject.AnalyticsPeriod;
import com.smarthealthfinance.cards.domain.valueobject.CardPosition;
import com.smarthealthfinance.cards.domain.valueobject.CardPosition.InstallmentCommitment;
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
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Debt ({@code health-v2}, ADR-0027 §3): b = parcelas já comprometidas na fatura seguinte à atual ÷ renda média
 * mensal em W e h = parcelas futuras ÷ renda média (meses de renda). Nota = 0,7 · S(b) + 0,3 · S(h). Sem parcelas,
 * 100; com parcelas e sem renda, 0.
 */
final class DebtDimension {

    static final ScoreCurve MONTHLY_CURVE = ScoreCurve.of("0", "100", "0.10", "84", "0.20", "60", "0.35", "25",
            "0.50", "0");
    static final ScoreCurve HORIZON_CURVE = ScoreCurve.of("0", "100", "1", "78", "3", "40", "6", "0");

    static final BigDecimal ATTENTION_MONTHLY = new BigDecimal("0.15");
    static final BigDecimal ATTENTION_HORIZON = BigDecimal.valueOf(3);

    private DebtDimension() {
    }

    static DimensionResult assess(HealthInput input, HealthWindow window) {
        List<CardPosition> cards = input.cards();
        if (cards.isEmpty()) {
            return DimensionResult.notApplicable(HealthDimension.DEBT);
        }
        LocalDate asOf = input.asOf();
        Money zero = Money.zero(cards.getFirst().limit().currency());
        Money next = zero;
        Money future = zero;
        List<InstallmentCommitment> commitments = new ArrayList<>();
        for (CardPosition card : cards) {
            next = next.plus(card.nextInvoiceCommitted());
            future = future.plus(card.futureInstallments());
            commitments.addAll(card.commitments());
        }

        List<Evidence> evidence = new ArrayList<>();
        evidence.add(new Evidence("nextInvoiceInstallments", "Parcelas na próxima fatura", EvidenceKind.MONEY,
                next.amount(), asOf, asOf, EvidenceSource.CARDS_INSTALLMENTS));
        evidence.add(new Evidence("futureInstallments", "Parcelas futuras", EvidenceKind.MONEY, future.amount(), asOf,
                asOf, EvidenceSource.CARDS_INSTALLMENTS));
        evidence.add(new Evidence("installmentPlans", "Parcelamentos em andamento", EvidenceKind.COUNT,
                BigDecimal.valueOf(commitments.size()), asOf, asOf, EvidenceSource.CARDS_INSTALLMENTS));

        if (!next.isPositive() && !future.isPositive()) {
            return DimensionResult.ok(HealthDimension.DEBT, 100, "Nenhum parcelamento em andamento nos seus cartões.",
                    false, evidence);
        }
        if (window.isEmpty()) {
            return DimensionResult.insufficient(HealthDimension.DEBT, InsufficientReason.NO_CLOSED_MONTH);
        }

        FlowLedger ledger = input.ledger();
        AnalyticsPeriod range = window.range();
        Money averageIncome = ledger.divide(ledger.total(CashFlowRole.INCOME, range), window.size());
        evidence.add(new Evidence("averageIncome", "Renda média mensal", EvidenceKind.MONEY, averageIncome.amount(),
                range.from(), range.to(), EvidenceSource.ANALYTICS_MONTHLY));

        BigDecimal monthly = null;
        BigDecimal horizon = null;
        int score;
        if (averageIncome.isPositive()) {
            monthly = Ratios.divide(next.amount(), averageIncome.amount());
            horizon = future.amount().divide(averageIncome.amount(), 2, RoundingMode.HALF_EVEN);
            score = Ratios.toScore(Ratios.weighted("0.7", MONTHLY_CURVE.score(monthly))
                    .add(Ratios.weighted("0.3", HORIZON_CURVE.score(horizon))));
            evidence.add(new Evidence("installmentShare", "Próxima fatura em parcelas ÷ renda média",
                    EvidenceKind.PERCENT, HealthTexts.toPercent(monthly).setScale(2), range.from(), asOf,
                    EvidenceSource.CARDS_INSTALLMENTS));
            evidence.add(new Evidence("incomeMonths", "Parcelas futuras em meses de renda", EvidenceKind.MONTHS,
                    horizon, range.from(), asOf, EvidenceSource.CARDS_INSTALLMENTS));
        }
        else {
            score = 0;
        }
        boolean attention = monthly == null || monthly.compareTo(ATTENTION_MONTHLY) >= 0
                || horizon.compareTo(ATTENTION_HORIZON) >= 0;

        String share = monthly == null ? "sem receitas no período"
                : HealthTexts.percent(HealthTexts.toPercent(monthly)) + " da renda média";
        String explanation;
        if (commitments.size() == 1) {
            InstallmentCommitment only = commitments.getFirst();
            explanation = "Único compromisso: %s, %d %s (%s até %s). Próxima fatura: %s (%s).".formatted(
                    label(input, only), only.remainingCount(),
                    only.remainingCount() == 1 ? "parcela restante" : "parcelas restantes",
                    HealthTexts.money(only.remainingAmount()), HealthTexts.month(only.lastDueMonth()),
                    HealthTexts.money(next), share);
        }
        else {
            explanation = "%d parcelamentos em andamento: %s na próxima fatura (%s) e %s em parcelas futuras.".formatted(
                    commitments.size(), HealthTexts.money(next), share, HealthTexts.money(future));
        }
        return DimensionResult.ok(HealthDimension.DEBT, score, explanation, attention, evidence);
    }

    /** Comerciante; sem ele, a categoria; nunca a descrição da compra (ADR-0021 §2). */
    static String label(HealthInput input, InstallmentCommitment commitment) {
        if (commitment.merchantName() != null) {
            return commitment.merchantName();
        }
        if (commitment.categoryCode() != null) {
            return input.categoryNames().getOrDefault(commitment.categoryCode(), "compra parcelada");
        }
        return "compra parcelada";
    }
}
