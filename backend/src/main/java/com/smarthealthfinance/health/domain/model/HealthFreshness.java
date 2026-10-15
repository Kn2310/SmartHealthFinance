package com.smarthealthfinance.health.domain.model;

import com.smarthealthfinance.health.domain.enums.HealthFreshnessState;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Freshness do snapshot do Health (ADR-0018 §7).
 *
 * @param computedAt           último recálculo; nulo se nunca
 * @param analyticsRefreshedAt último recálculo da projeção de Analytics que o Health lê; nulo se nunca
 */
public record HealthFreshness(HealthFreshnessState state, Instant computedAt, Instant analyticsRefreshedAt) {

    public HealthFreshness {
        Objects.requireNonNull(state, "state");
    }

    /**
     * @param built             há snapshot na versão atual
     * @param hasTransactions   o Workspace tem transações
     * @param dirty             marcado para recálculo
     * @param pendingEvents     eventos do Health ainda não marcados
     * @param analyticsUpdating a projeção de Analytics ainda está processando eventos
     * @param latestAsOf        maior data-base gravada; nula sem snapshot
     * @param today             hoje no fuso de negócio
     */
    public static HealthFreshness resolve(boolean built, boolean hasTransactions, boolean dirty, int pendingEvents,
                                          boolean analyticsUpdating, LocalDate latestAsOf, LocalDate today,
                                          Instant computedAt, Instant analyticsRefreshedAt) {
        HealthFreshnessState state;
        if (!built) {
            state = hasTransactions ? HealthFreshnessState.NOT_BUILT : HealthFreshnessState.UP_TO_DATE;
        }
        else if (dirty || pendingEvents > 0 || analyticsUpdating
                || (latestAsOf != null && latestAsOf.isBefore(today))) {
            state = HealthFreshnessState.RECALCULATING;
        }
        else {
            state = HealthFreshnessState.UP_TO_DATE;
        }
        return new HealthFreshness(state, computedAt, analyticsRefreshedAt);
    }
}
