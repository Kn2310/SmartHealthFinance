package com.smarthealthfinance.transactions.domain.valueobject;

import com.smarthealthfinance.transactions.domain.enums.CategorySource;

import java.util.Objects;

/**
 * Como a categoria foi atribuída (ADR-0013 §3). {@code ruleVersion} só existe para {@code RULE_SYSTEM}.
 * Uma origem {@code MANUAL} sem categoria significa "o usuário escolheu Sem categoria": não é reclassificada.
 */
public record CategoryOrigin(CategorySource source, String ruleVersion) {

    public static final CategoryOrigin MANUAL = new CategoryOrigin(CategorySource.MANUAL, null);
    public static final CategoryOrigin LEARNED = new CategoryOrigin(CategorySource.RULE_LEARNED, null);

    public CategoryOrigin {
        Objects.requireNonNull(source, "source");
        if ((source == CategorySource.RULE_SYSTEM) != (ruleVersion != null)) {
            throw new IllegalArgumentException("ruleVersion existe só para RULE_SYSTEM");
        }
    }

    public static CategoryOrigin system(String ruleVersion) {
        return new CategoryOrigin(CategorySource.RULE_SYSTEM, ruleVersion);
    }
}
