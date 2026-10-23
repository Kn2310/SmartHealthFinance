package com.smarthealthfinance.transactions.domain.repository;

import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.transactions.domain.enums.TransactionStatus;
import com.smarthealthfinance.transactions.domain.enums.TransactionType;
import com.smarthealthfinance.transactions.domain.valueobject.CardReference;
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
 * @param cardId        cartão de origem (compra, estorno) OU de destino (pagamento de fatura), ADR-0026 §10
 */
public record TransactionCriteria(
        LocalDate from,
        LocalDate to,
        TransactionType type,
        TransactionStatus status,
        AccountId accountId,
        String text,
        CategoryCode categoryCode,
        boolean uncategorized,
        CardReference cardId
) {
    public TransactionCriteria {
        if (categoryCode != null && uncategorized) {
            throw new IllegalArgumentException("categoryCode e uncategorized são exclusivos");
        }
    }

    /** Sem filtro de cartão. */
    public TransactionCriteria(LocalDate from, LocalDate to, TransactionType type, TransactionStatus status,
                               AccountId accountId, String text, CategoryCode categoryCode, boolean uncategorized) {
        this(from, to, type, status, accountId, text, categoryCode, uncategorized, null);
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
