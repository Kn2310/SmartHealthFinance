package com.smarthealthfinance.transactions.domain.repository;

import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.transactions.domain.enums.TransactionStatus;
import com.smarthealthfinance.transactions.domain.enums.TransactionType;

import java.time.LocalDate;

/**
 * Filtros da listagem; campos nulos não filtram.
 *
 * @param from      occurredOn mínimo (inclusivo)
 * @param to        occurredOn máximo (inclusivo)
 * @param accountId conta de origem OU de destino (transferências)
 * @param text      trecho da descrição, sem diferenciar maiúsculas; tratado como literal
 */
public record TransactionCriteria(
        LocalDate from,
        LocalDate to,
        TransactionType type,
        TransactionStatus status,
        AccountId accountId,
        String text
) {
    public static TransactionCriteria none() {
        return new TransactionCriteria(null, null, null, null, null, null);
    }
}
