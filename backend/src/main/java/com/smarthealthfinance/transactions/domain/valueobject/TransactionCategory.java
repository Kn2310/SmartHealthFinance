package com.smarthealthfinance.transactions.domain.valueobject;

import com.smarthealthfinance.transactions.domain.enums.CategoryNature;

import java.util.Objects;

/**
 * Categoria atribuída a uma transação. A natureza viaja junto com o código para que o banco também garanta a
 * coerência tipo × natureza (FK composta, ADR-0012 §10). Hábito de consumo: nunca vai para logs.
 */
public record TransactionCategory(
        CategoryCode code,
        CategoryNature nature
) {
    public TransactionCategory {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(nature, "nature");
    }
}
