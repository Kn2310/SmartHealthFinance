package com.smarthealthfinance.transactions.domain.enums;

import java.util.Optional;

/**
 * Natureza de uma categoria (ADR-0012 §2). Despesa e reembolso (devolução de despesa) usam categorias de despesa;
 * receita usa categorias de receita. Transferência e ajuste nunca têm categoria.
 */
public enum CategoryNature {

    EXPENSE,
    INCOME;

    /** @return a natureza aceita pelo tipo, ou vazio se o tipo não admite categoria */
    public static Optional<CategoryNature> acceptedBy(TransactionType type) {
        return switch (type) {
            case EXPENSE, REFUND -> Optional.of(EXPENSE);
            case INCOME -> Optional.of(INCOME);
            case TRANSFER, ADJUSTMENT -> Optional.empty();
        };
    }
}
