package com.smarthealthfinance.overview.domain.enums;

import com.smarthealthfinance.shared.domain.InvalidValueException;

/** Períodos aceitos pelo Overview (ADR-0006). */
public enum PeriodType {

    /** Do dia 1º do mês corrente até hoje (month-to-date). */
    CURRENT_MONTH,
    /** Mês civil anterior, completo. */
    PREVIOUS_MONTH,
    /** Intervalo informado pelo cliente, inclusivo nas duas pontas. */
    CUSTOM;

    /** Ausente (nulo/em branco) assume {@link #CURRENT_MONTH}. */
    public static PeriodType parseOrDefault(String value) {
        if (value == null || value.isBlank()) {
            return CURRENT_MONTH;
        }

        try {
            return valueOf(value.strip());
        }
        catch (IllegalArgumentException ex) {
            throw new InvalidValueException("period", "INVALID");
        }
    }
}
