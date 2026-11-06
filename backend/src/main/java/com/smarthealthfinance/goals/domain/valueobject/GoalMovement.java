package com.smarthealthfinance.goals.domain.valueobject;

import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.goals.domain.enums.GoalMovementKind;
import com.smarthealthfinance.goals.domain.enums.MovementDirection;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionId;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * Um movimento {@code POSTED} do destino desde o início do acompanhamento (ADR-0034 §2.3). O valor é sempre positivo;
 * {@code direction} diz se entrou ou saiu.
 *
 * @param counterpartAccountId a outra conta da transferência (origem do aporte, destino do resgate); vazio quando
 *                             não há (resgate para cartão ou movimento que não é transferência)
 */
public record GoalMovement(TransactionId transactionId, GoalMovementKind kind, MovementDirection direction,
                           LocalDate occurredOn, Money amount, AccountId counterpartAccountId) {

    public GoalMovement {
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(occurredOn, "occurredOn");
        Objects.requireNonNull(amount, "amount");
    }

    public Optional<AccountId> counterpart() {
        return Optional.ofNullable(counterpartAccountId);
    }
}
