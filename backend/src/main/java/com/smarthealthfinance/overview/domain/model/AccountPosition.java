package com.smarthealthfinance.overview.domain.model;

import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.shared.domain.Money;

import java.util.Collection;
import java.util.Currency;
import java.util.Objects;

/** Posição derivada de uma conta (nunca armazenada): saldo até a data-base e movimentações do período. */
public record AccountPosition(AccountId accountId, boolean includedInTotal, Money balance, int movementCount) {

    public AccountPosition {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(balance, "balance");
        if (movementCount < 0) {
            throw new IllegalArgumentException("movementCount");
        }
    }

    /** Saldo total = soma das contas incluídas no total (a lista já traz apenas contas ativas). */
    public static Money totalOf(Collection<AccountPosition> positions, Currency currency) {
        return positions.stream()
                .filter(AccountPosition::includedInTotal)
                .map(AccountPosition::balance)
                .reduce(Money.zero(currency), Money::plus);
    }
}
