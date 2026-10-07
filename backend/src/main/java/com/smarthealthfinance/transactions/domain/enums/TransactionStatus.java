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

    /** Só o que foi lançado move saldo: PENDING ainda não aconteceu e as anuladas não produzem efeito (ADR-0006). */
    public boolean affectsBalance() {
        return this == POSTED;
    }

    /** Anulada: não produz efeito financeiro. */
    public boolean isVoided() {
        return this == CANCELLED || this == REVERSED;
    }
}
