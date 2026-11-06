package com.smarthealthfinance.goals.domain.valueobject;

import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.goals.domain.enums.DestinationKind;

import java.util.Objects;
import java.util.Optional;

/**
 * Destino tipado do objetivo (ADR-0034 §2.1, §9): {@code ACCOUNT} com a conta dedicada, ou {@code RESERVE} (as contas
 * {@code SAVINGS} ativas), sem conta.
 */
public record GoalDestination(DestinationKind kind, AccountId accountId) {

    public GoalDestination {
        Objects.requireNonNull(kind, "kind");
        if (kind == DestinationKind.ACCOUNT) {
            Objects.requireNonNull(accountId, "accountId");
        } else if (accountId != null) {
            throw new IllegalArgumentException("RESERVE não tem conta");
        }
    }

    public static GoalDestination account(AccountId accountId) {
        return new GoalDestination(DestinationKind.ACCOUNT, accountId);
    }

    public static GoalDestination reserve() {
        return new GoalDestination(DestinationKind.RESERVE, null);
    }

    public Optional<AccountId> account() {
        return Optional.ofNullable(accountId);
    }
}
