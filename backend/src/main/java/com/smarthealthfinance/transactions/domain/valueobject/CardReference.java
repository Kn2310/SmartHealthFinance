package com.smarthealthfinance.transactions.domain.valueobject;

import java.util.Objects;
import java.util.UUID;

/**
 * Cartão de crédito onde a transação aconteceu, como referência opaca (ADR-0025 §2). Transactions não conhece o módulo
 * Cards: o cartão é validado por quem cria o lançamento, e a FK composta com {@code workspace_id} garante no banco que
 * ele é do mesmo Workspace.
 */
public record CardReference(
        UUID value
) {
    public CardReference {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
