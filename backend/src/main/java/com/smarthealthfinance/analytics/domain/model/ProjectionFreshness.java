package com.smarthealthfinance.analytics.domain.model;

import com.smarthealthfinance.analytics.domain.enums.ProjectionState;

import java.time.Instant;
import java.util.Objects;

/**
 * Quão atual está a projeção de um Workspace (ADR-0014 §6).
 *
 * @param refreshedAt   último recálculo (rebuild ou mês); {@code null} se nunca construída
 * @param pendingEvents eventos do Workspace posteriores ao último rebuild que o consumidor ainda não processou
 */
public record ProjectionFreshness(ProjectionState state, Instant refreshedAt, int pendingEvents) {

    public ProjectionFreshness {
        Objects.requireNonNull(state, "state");
        if (pendingEvents < 0) {
            throw new IllegalArgumentException("pendingEvents negativo");
        }
    }

    /**
     * @param built         a projeção já foi construída na versão atual
     * @param hasPostedData o Workspace tem transações que a projeção precisaria refletir
     */
    public static ProjectionFreshness resolve(boolean built, boolean hasPostedData, Instant refreshedAt,
                                              int pendingEvents) {
        if (!built) {
            return new ProjectionFreshness(hasPostedData ? ProjectionState.NOT_BUILT : ProjectionState.UP_TO_DATE,
                    refreshedAt, pendingEvents);
        }
        return new ProjectionFreshness(pendingEvents > 0 ? ProjectionState.UPDATING : ProjectionState.UP_TO_DATE,
                refreshedAt, pendingEvents);
    }
}
