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
import java.math.RoundingMode;
import java.util.List;

/**
 * Reserve (ADR-0017 §2): cobertura c = soma dos saldos das contas {@code SAVINGS} ativas na data-base (mesmo fora do
 * total) ÷ média mensal de despesas em W (zeros contam), com 2 casas. Referência: 6 meses.
 */
final class ReserveDimension {

    static final ScoreCurve CURVE = ScoreCurve.of("0", "0", "1", "45", "3", "80", "6", "100");
    static final BigDecimal REFERENCE_MONTHS = BigDecimal.valueOf(6);

    private static final BigDecimal ATTENTION_MONTHS = BigDecimal.valueOf(3);

    /**
     * @param noReserveAccount sem conta de reserva (próximo passo "Indique onde fica sua reserva")
     * @param gap              quanto falta para 6 meses de despesas; nulo se c ≥ 6 ou sem nota
     */
    record Assessment(DimensionResult result, boolean noReserveAccount, Money gap) {}

    private ReserveDimension() {
    }

    static Assessment assess(HealthInput input, HealthWindow window) {
        if (input.reserves().isEmpty()) {
            return new Assessment(DimensionResult.insufficient(HealthDimension.RESERVE,
                    InsufficientReason.NO_RESERVE_ACCOUNT), true, null);
        }
        if (window.isEmpty()) {
            return new Assessment(DimensionResult.insufficient(HealthDimension.RESERVE,
                    InsufficientReason.NO_CLOSED_MONTH), false, null);
        }
        FlowLedger ledger = input.ledger();
        AnalyticsPeriod range = window.range();
        Money averageExpense = ledger.divide(ledger.total(CashFlowRole.EXPENSE, range), window.size());
        if (averageExpense.amount().signum() == 0) {
            return new Assessment(DimensionResult.insufficient(HealthDimension.RESERVE,
                    InsufficientReason.ZERO_EXPENSE_AVERAGE), false, null);
        }
        Money reserve = input.reserves().stream().map(HealthInput.ReserveBalance::balance)
                .reduce(ledger.zero(), Money::plus);

        BigDecimal coverage = reserve.amount().signum() <= 0 ? BigDecimal.ZERO.setScale(2)
                : reserve.amount().divide(averageExpense.amount(), 2, RoundingMode.HALF_EVEN);
        int score = reserve.amount().signum() <= 0 ? 0 : Ratios.toScore(CURVE.score(coverage));

        Money target = new Money(averageExpense.amount().multiply(REFERENCE_MONTHS), averageExpense.currency());
        Money gap = coverage.compareTo(REFERENCE_MONTHS) >= 0 || !target.isGreaterThan(reserve) ? null
                : target.minus(reserve);

        String explanation = ("Sua reserva cobre ≈ %s meses da média de despesas (%s/mês, %s). "
                + "Referência: 6 meses.").formatted(HealthTexts.oneDecimal(coverage),
                HealthTexts.money(averageExpense), HealthTexts.months(window.months()));

        List<Evidence> evidence = List.of(
                new Evidence("reserveBalance", "Saldo das contas de reserva", EvidenceKind.MONEY, reserve.amount(),
                        input.asOf(), input.asOf(), EvidenceSource.OVERVIEW_ACCOUNT_BALANCES),
                new Evidence("reserveAccounts", "Contas de reserva", EvidenceKind.COUNT,
                        BigDecimal.valueOf(input.reserves().size()), input.asOf(), input.asOf(),
                        EvidenceSource.OVERVIEW_ACCOUNT_BALANCES),
                new Evidence("averageExpense", "Média mensal de despesas", EvidenceKind.MONEY,
                        averageExpense.amount(), range.from(), range.to(), EvidenceSource.ANALYTICS_MONTHLY),
                new Evidence("coverageMonths", "Meses de cobertura", EvidenceKind.MONTHS, coverage, range.from(),
                        input.asOf(), EvidenceSource.OVERVIEW_ACCOUNT_BALANCES));

        return new Assessment(DimensionResult.ok(HealthDimension.RESERVE, score, explanation,
                coverage.compareTo(ATTENTION_MONTHS) < 0, evidence), false, gap);
    }
}
