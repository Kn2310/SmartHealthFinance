package com.smarthealthfinance.forecast.domain.model;

import com.smarthealthfinance.forecast.domain.enums.Confidence;
import com.smarthealthfinance.forecast.domain.enums.Limitation;
import com.smarthealthfinance.shared.domain.Money;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;

/**
 * Fluxo de caixa projetado do saldo total (ADR-0030 §3.4): do mês corrente até o fim do mês corrente + 3, lente de
 * caixa. O ponto de partida é o saldo de hoje (fato); a faixa é acumulada mês a mês.
 *
 * @param trough       menor saldo do horizonte e a data
 * @param troughNext30 o mesmo, só nos próximos 30 dias (insumo do D15, ADR-0030 §8)
 */
public record CashFlowProjection(LocalDate from, LocalDate to, List<CashFlowMonth> months, Trough trough,
                                 Trough troughNext30, Confidence confidence, List<Limitation> limitations,
                                 List<Evidence> evidence) {

    public CashFlowProjection {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(trough, "trough");
        Objects.requireNonNull(troughNext30, "troughNext30");
        Objects.requireNonNull(confidence, "confidence");
        months = List.copyOf(months);
        limitations = List.copyOf(limitations);
        evidence = List.copyOf(evidence);
    }

    /**
     * Um mês do horizonte. As entradas e saídas são só dos dias projetados (no mês corrente, depois de hoje). O saldo
     * final soma o saldo de hoje e todos os meses até este.
     */
    public record CashFlowMonth(YearMonth month, Money opening, Money inflowFact, Money inflowEstimated,
                                Money outflowFact, Money outflowEstimated, Projected closing) {

        public CashFlowMonth {
            Objects.requireNonNull(month, "month");
            Objects.requireNonNull(closing, "closing");
        }
    }

    /** Saldo projetado num dia, com a faixa. */
    public record BalancePoint(LocalDate date, Money central, Money low, Money high) {}

    /**
     * @param lowestCentral o dia de menor saldo central (empate: o primeiro)
     * @param lowestLowBound o dia de menor limite baixo (empate: o primeiro)
     */
    public record Trough(BalancePoint lowestCentral, BalancePoint lowestLowBound) {}
}
