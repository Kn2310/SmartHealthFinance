package com.smarthealthfinance.analytics.domain.enums;

/** Linha de saída da composição do período (ADR-0015 §5). */
public enum OutflowGroup {

    /** Uma categoria do catálogo. */
    CATEGORY,
    /** Despesas sem categoria. */
    UNCATEGORIZED,
    /** As demais categorias, somadas. */
    OTHERS
}
