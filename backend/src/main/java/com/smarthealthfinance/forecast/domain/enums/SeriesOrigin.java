package com.smarthealthfinance.forecast.domain.enums;

/** Quem criou a série. Criação manual está fora do v1, mas o campo existe no modelo (ADR-0030 §2). */
public enum SeriesOrigin {
    DETECTED,
    USER
}
