package com.smarthealthfinance.health.domain.enums;

/** Situação do resultado de um mês (ADR-0017 §3). */
public enum HealthState {
    /** Há score geral. */
    READY,
    /** Sem Cash Flow {@code OK} e mais uma dimensão {@code OK}: só as dimensões disponíveis, sem score. */
    INSUFFICIENT_DATA
}
