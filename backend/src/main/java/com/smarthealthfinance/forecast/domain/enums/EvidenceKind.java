package com.smarthealthfinance.forecast.domain.enums;

/** Tipo de evidência rastreável de um valor estimado (ADR-0030 §6). */
public enum EvidenceKind {

    /** Um mês de amostra e o valor dele. */
    SAMPLE_MONTH,
    /** Uma transação que ocorreu numa série. */
    SERIES_OCCURRENCE,
    /** Uma fatura de base: cartão e mês de fechamento. */
    BASE_INVOICE
}
