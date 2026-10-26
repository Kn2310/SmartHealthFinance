package com.smarthealthfinance.cards.domain.valueobject;

import java.util.Objects;
import java.util.UUID;

/** Uma parcela de uma compra no cartão: a compra e o número da parcela (ADR-0025 §6). */
public record InstallmentKey(UUID transactionId, int number) {

    public InstallmentKey {
        Objects.requireNonNull(transactionId, "transactionId");
        if (number < 1) {
            throw new IllegalArgumentException("number");
        }
    }
}
