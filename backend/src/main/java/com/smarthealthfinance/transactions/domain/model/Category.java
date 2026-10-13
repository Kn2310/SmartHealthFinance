package com.smarthealthfinance.transactions.domain.model;

import com.smarthealthfinance.transactions.domain.enums.CategoryNature;
import com.smarthealthfinance.transactions.domain.valueobject.CategoryCode;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionCategory;

import java.util.Objects;

/**
 * Categoria do catálogo do sistema (ADR-0012 §1): dado de referência gravado por migration, igual para todos os
 * Workspaces. Nunca é apagada; quando sai do catálogo, fica inativa e só continua visível nas transações antigas.
 *
 * @param displayOrder ordem de exibição no catálogo
 */
public record Category(
        CategoryCode code,
        CategoryNature nature,
        String name,
        int displayOrder,
        boolean active
) {
    public Category {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(nature, "nature");
        Objects.requireNonNull(name, "name");
    }

    public TransactionCategory asTransactionCategory() {
        return new TransactionCategory(code, nature);
    }
}
