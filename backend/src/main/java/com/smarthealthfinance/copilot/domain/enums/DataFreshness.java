package com.smarthealthfinance.copilot.domain.enums;

/** Freshness dos read models usados no contexto (ADR-0039 §5), na forma comum aos módulos. */
public enum DataFreshness {
    UP_TO_DATE,
    UPDATING,
    NOT_BUILT;

    /** O pior dos dois: um read model não construído domina um em atualização. */
    public DataFreshness worst(DataFreshness other) {
        return ordinal() >= other.ordinal() ? this : other;
    }
}
