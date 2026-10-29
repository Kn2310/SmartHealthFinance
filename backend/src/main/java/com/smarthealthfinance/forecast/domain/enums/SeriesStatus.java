package com.smarthealthfinance.forecast.domain.enums;

/**
 * Estado da série para o usuário (ADR-0030 §2). {@code STALE} não é um status: é derivado de "hoje" e do histórico
 * ({@link SeriesEligibility}).
 */
public enum SeriesStatus {

    /** Sugerida pela detecção. */
    DETECTED,
    CONFIRMED,
    /** O mesmo {@code direction + key} não volta a ser sugerido. */
    IGNORED
}
