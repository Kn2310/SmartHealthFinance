package com.smarthealthfinance.insights.domain.enums;

/** Estado de {@code .../insights/status} (ADR-0022 §8). */
public enum InsightsFreshnessState {
    UP_TO_DATE,
    /** Geração pendente ou uma das fontes (Health, Analytics) ainda processando. */
    UPDATING,
    /** Há transações e nenhuma geração na versão atual. */
    NOT_BUILT
}
