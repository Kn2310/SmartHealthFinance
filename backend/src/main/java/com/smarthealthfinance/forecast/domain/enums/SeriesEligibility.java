package com.smarthealthfinance.forecast.domain.enums;

/** Situação de uma série diante das projeções (ADR-0030 §2). */
public enum SeriesEligibility {

    /** Entra nas projeções. */
    ELIGIBLE,
    /** Série confirmada sem ocorrência há 3 meses: sai das projeções até voltar a ocorrer. */
    STALE,
    /** Ignorada, de baixa confiança ou que deixou de ser candidata. */
    NOT_ELIGIBLE
}
