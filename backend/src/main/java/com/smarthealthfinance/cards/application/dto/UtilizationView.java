package com.smarthealthfinance.cards.application.dto;

import com.smarthealthfinance.cards.domain.valueobject.CardUtilization;
import com.smarthealthfinance.shared.domain.Money;

import java.time.LocalDate;

/** Utilização do limite (ADR-0025 §9, ADR-0026 §6). */
public record UtilizationView(
        Money limit, Money utilized, Money available, Money credit, int utilizationPercent,
        int utilizationWithoutFuturePercent, Composition composition, LocalDate asOf, String calculationVersion
) {
    public record Composition(Money previousOutstanding, Money currentInvoice, Money futureInstallments) {}

    public static UtilizationView from(CardUtilization utilization, LocalDate asOf, String calculationVersion) {
        return new UtilizationView(utilization.limit(), utilization.utilized(), utilization.available(),
                utilization.credit(), utilization.utilizationPercent(),
                utilization.utilizationWithoutFuturePercent(),
                new Composition(utilization.previousOutstanding(), utilization.currentInvoice(),
                        utilization.futureInstallments()),
                asOf, calculationVersion);
    }
}
