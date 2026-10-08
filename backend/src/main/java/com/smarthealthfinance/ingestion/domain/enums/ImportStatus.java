package com.smarthealthfinance.ingestion.domain.enums;

/**
 * Ciclo de vida de um batch (ADR-0009 §2).
 *
 * <pre>
 * PREVIEW ──confirm──▶ CONFIRMED ──worker──▶ PROCESSING ──▶ COMPLETED
 *    │                                            └───────▶ FAILED
 *    ├──cancel──▶ CANCELLED
 *    └──24h─────▶ EXPIRED
 * </pre>
 */
public enum ImportStatus {
    PREVIEW,
    CONFIRMED,
    PROCESSING,
    COMPLETED,
    FAILED,
    CANCELLED,
    EXPIRED;

    /** Já passou pela confirmação do usuário (repetir o confirm não tem efeito). */
    public boolean wasConfirmed() {
        return this == CONFIRMED || this == PROCESSING || this == COMPLETED || this == FAILED;
    }

    /** O worker ainda precisa agir (inclusive numa reentrega depois de uma falha no meio do processamento). */
    public boolean awaitsProcessing() {
        return this == CONFIRMED || this == PROCESSING;
    }
}
