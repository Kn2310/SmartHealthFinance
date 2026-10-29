package com.smarthealthfinance.forecast.domain.model;

import com.smarthealthfinance.forecast.domain.enums.ForecastFreshnessState;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Freshness do snapshot do Forecast (ADR-0031 §9).
 *
 * @param computedAt           último recálculo; nulo se nunca
 * @param asOf                 data-base do snapshot atual; nula sem snapshot
 * @param analyticsRefreshedAt último recálculo da projeção de Analytics que o Forecast lê; nulo se nunca
 */
public record ForecastFreshness(ForecastFreshnessState state, Instant computedAt, LocalDate asOf,
                                Instant analyticsRefreshedAt) {

    public ForecastFreshness {
        Objects.requireNonNull(state, "state");
    }

    /**
     * @param built             há snapshot na versão atual
     * @param hasTransactions   o Workspace tem transações
     * @param dirty             marcado para recálculo
     * @param pendingEvents     eventos do Forecast ainda não marcados pelo consumidor
     * @param analyticsUpdating a projeção de Analytics ainda está processando eventos
     * @param latestAsOf        data-base do snapshot; nula sem snapshot
     * @param today             hoje no fuso de negócio
     */
    public static ForecastFreshness resolve(boolean built, boolean hasTransactions, boolean dirty, int pendingEvents,
                                            boolean analyticsUpdating, LocalDate latestAsOf, LocalDate today,
                                            Instant computedAt, Instant analyticsRefreshedAt) {
        ForecastFreshnessState state;
        if (!built) {
            state = hasTransactions ? ForecastFreshnessState.NOT_BUILT : ForecastFreshnessState.UP_TO_DATE;
        }
        else if (dirty || pendingEvents > 0 || analyticsUpdating
                || (latestAsOf != null && latestAsOf.isBefore(today))) {
            state = ForecastFreshnessState.RECALCULATING;
        }
        else {
            state = ForecastFreshnessState.UP_TO_DATE;
        }
        return new ForecastFreshness(state, computedAt, latestAsOf, analyticsRefreshedAt);
    }
}
