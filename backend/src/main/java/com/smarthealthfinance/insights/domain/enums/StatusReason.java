package com.smarthealthfinance.insights.domain.enums;

/** Por que um insight saiu de {@code ACTIVE} sem ação do usuário (ADR-0022 §4). */
public enum StatusReason {
    /** O período avaliado mudou (dia 7 do mês seguinte). */
    PERIOD_ENDED,
    /** Uma nova versão do catálogo gerou os insights do período. */
    VERSION_SUPERSEDED
}
