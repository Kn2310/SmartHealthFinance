package com.smarthealthfinance.health.domain.model;

import com.smarthealthfinance.health.domain.enums.DimensionStatus;
import com.smarthealthfinance.health.domain.enums.HealthDimension;
import com.smarthealthfinance.health.domain.enums.InsufficientReason;
import java.util.List;
import java.util.Objects;

/**
 * Nota de uma dimensão (ADR-0017 §2).
 *
 * @param score       0–100, só com {@code OK}
 * @param reason      só com {@code INSUFFICIENT_DATA}/{@code NOT_APPLICABLE}
 * @param explanation template com os números reais; sem nota, o texto do motivo
 * @param attention   "Ponto de atenção" da dimensão
 */
public record DimensionResult(HealthDimension dimension, DimensionStatus status, Integer score,
                              InsufficientReason reason, String explanation, boolean attention,
                              List<Evidence> evidence) {

    public DimensionResult {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(explanation, "explanation");
        evidence = List.copyOf(evidence);
        if ((status == DimensionStatus.OK) != (score != null)) {
            throw new IllegalArgumentException("Só dimensões OK têm nota");
        }
        if ((status == DimensionStatus.OK) == (reason != null)) {
            throw new IllegalArgumentException("Dimensão sem nota precisa de motivo");
        }
        if (score != null && (score < 0 || score > 100)) {
            throw new IllegalArgumentException("Nota fora de 0–100");
        }
    }

    public static DimensionResult ok(HealthDimension dimension, int score, String explanation, boolean attention,
                                     List<Evidence> evidence) {
        return new DimensionResult(dimension, DimensionStatus.OK, score, null, explanation, attention, evidence);
    }

    public static DimensionResult insufficient(HealthDimension dimension, InsufficientReason reason) {
        return new DimensionResult(dimension, DimensionStatus.INSUFFICIENT_DATA, null, reason, reason.text(), false,
                List.of());
    }

    public static DimensionResult notApplicable(HealthDimension dimension) {
        return new DimensionResult(dimension, DimensionStatus.NOT_APPLICABLE, null, InsufficientReason.REQUIRES_CARDS,
                InsufficientReason.REQUIRES_CARDS.text(), false, List.of());
    }

    public boolean isOk() {
        return status == DimensionStatus.OK;
    }
}
