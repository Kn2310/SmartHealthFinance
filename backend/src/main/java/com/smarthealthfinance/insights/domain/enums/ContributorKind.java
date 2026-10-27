package com.smarthealthfinance.insights.domain.enums;

/** Tipo de um "maior contribuinte" das evidências (ADR-0021 §7). */
public enum ContributorKind {
    MERCHANT,
    CATEGORY,
    TRANSACTION,
    /** Uma cobrança mensal de um comerciante recorrente (D3). */
    CHARGE,
    /** Um fator da diferença da fatura: categoria, parcelamento, estornos ou outros (D11). */
    FACTOR
}
