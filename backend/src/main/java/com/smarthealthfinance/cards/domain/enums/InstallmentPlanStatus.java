package com.smarthealthfinance.cards.domain.enums;

/** Situação de um parcelamento, derivada das parcelas. */
public enum InstallmentPlanStatus {
    /** Ainda tem parcela atual ou futura. */
    ACTIVE,
    /** Todas as parcelas pagas (ou canceladas por estorno parcial). */
    FINISHED,
    /** Todas as parcelas consumidas por estorno. */
    CANCELLED
}
