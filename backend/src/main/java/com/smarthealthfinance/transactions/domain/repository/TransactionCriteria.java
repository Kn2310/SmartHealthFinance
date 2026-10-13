package com.smarthealthfinance.transactions.domain.repository;

import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.transactions.domain.enums.TransactionStatus;
import com.smarthealthfinance.transactions.domain.enums.TransactionType;
import com.smarthealthfinance.transactions.domain.valueobject.CategoryCode;

import java.time.LocalDate;

/**
 * Filtros da listagem; campos nulos (e {@code uncategorized} falso) não filtram.
 *
 * @param from          occurredOn mínimo (inclusivo)
 * @param to            occurredOn máximo (inclusivo)
 * @param accountId     conta de origem OU de destino (transferências)
 * @param text          trecho da descrição, sem diferenciar maiúsculas; tratado como literal
 * @param categoryCode  só transações desta categoria (ADR-0012 §9)
 * @param uncategorized só transações sem categoria; exclusivo com {@code categoryCode}
 */
public record TransactionCriteria(
        LocalDate from,
        LocalDate to,
        TransactionType type,
        TransactionStatus status,
        AccountId accountId,
        String text,
        CategoryCode categoryCode,
        boolean uncategorized
) {
    public TransactionCriteria {
        if (categoryCode != null && uncategorized) {
            throw new IllegalArgumentException("categoryCode e uncategorized são exclusivos");
        }
    }

    /** Sem filtro de categoria. */
    public TransactionCriteria(LocalDate from, LocalDate to, TransactionType type, TransactionStatus status,
                               AccountId accountId, String text) {
        this(from, to, type, status, accountId, text, null, false);
    }

    public static TransactionCriteria none() {
        return new TransactionCriteria(null, null, null, null, null, null);
    }
}
