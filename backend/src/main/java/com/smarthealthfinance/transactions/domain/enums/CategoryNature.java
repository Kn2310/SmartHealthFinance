package com.smarthealthfinance.transactions.domain.enums;

import java.util.List;
import java.util.Optional;

/**
 * Natureza de uma categoria (ADR-0012 §2). Despesa e reembolso (devolução de despesa) usam categorias de despesa;
 * receita usa categorias de receita. Transferência e ajuste nunca têm categoria.
 */
public enum CategoryNature {

    EXPENSE,
    INCOME;

    /** Tipos de transação que aceitam categorias desta natureza. */
    public List<TransactionType> acceptingTypes() {
        return this == INCOME ? List.of(TransactionType.INCOME) : List.of(TransactionType.EXPENSE, TransactionType.REFUND);
    }

    /** @return a natureza aceita pelo tipo, ou vazio se o tipo não admite categoria */
    public static Optional<CategoryNature> acceptedBy(TransactionType type) {
        return switch (type) {
            case EXPENSE, REFUND -> Optional.of(EXPENSE);
            case INCOME -> Optional.of(INCOME);
            case TRANSFER, ADJUSTMENT -> Optional.empty();
        };
    }
}
