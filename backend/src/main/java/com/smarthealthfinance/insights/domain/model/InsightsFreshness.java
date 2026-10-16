package com.smarthealthfinance.insights.domain.model;

import com.smarthealthfinance.insights.domain.enums.InsightsFreshnessState;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Freshness dos insights (ADR-0022 §8).
 *
 * @param generatedAt      última geração; nula se nunca
 * @param healthComputedAt último recálculo do snapshot do Health que os insights leem; nulo se nunca
 */
public record InsightsFreshness(InsightsFreshnessState state, Instant generatedAt, Instant healthComputedAt) {

    public InsightsFreshness {
        Objects.requireNonNull(state, "state");
    }

    /**
     * @param built             última geração na versão atual
     * @param hasTransactions   o Workspace tem transações
     * @param busy              Workspace marcado ou eventos dos Insights pendentes
     * @param sourcesUpdating   Health ou Analytics ainda processando
     * @param asOf              data de negócio da última geração; nula se nunca
     * @param today             hoje no fuso de negócio
     */
    public static InsightsFreshness resolve(boolean built, boolean hasTransactions, boolean busy,
                                            boolean sourcesUpdating, LocalDate asOf, LocalDate today,
                                            Instant generatedAt, Instant healthComputedAt) {
        InsightsFreshnessState state;
        if (!built) {
            state = hasTransactions ? InsightsFreshnessState.NOT_BUILT : InsightsFreshnessState.UP_TO_DATE;
        }
        else if (busy || sourcesUpdating || (asOf != null && asOf.isBefore(today))) {
            state = InsightsFreshnessState.UPDATING;
        }
        else {
            state = InsightsFreshnessState.UP_TO_DATE;
        }
        return new InsightsFreshness(state, generatedAt, healthComputedAt);
    }
}
