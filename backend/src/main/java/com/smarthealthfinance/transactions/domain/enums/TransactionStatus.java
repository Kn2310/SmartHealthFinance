package com.smarthealthfinance.transactions.domain.enums;

import com.smarthealthfinance.shared.domain.InvalidValueException;

/**
 * Ciclo de vida (spec 05.4, ADR-0005): PENDING → POSTED | CANCELLED; POSTED → REVERSED.
 * CANCELLED e REVERSED são terminais.
 */
public enum TransactionStatus {

    PENDING,
    POSTED,
    CANCELLED,
    REVERSED;

    public static TransactionStatus parse(String value) {
        if (value == null || value.isBlank()) {
            throw new InvalidValueException("status", "REQUIRED");
        }

        try {
            return valueOf(value.strip());
        }
        catch (IllegalArgumentException ex) {
            throw new InvalidValueException("status", "INVALID");
        }
    }

    /** Anulada: não produz efeito financeiro. */
    public boolean isVoided() {
        return this == CANCELLED || this == REVERSED;
    }
}
