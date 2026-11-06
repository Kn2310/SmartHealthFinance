package com.smarthealthfinance.goals.domain.service;

import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.goals.domain.enums.GoalMovementKind;
import com.smarthealthfinance.goals.domain.enums.MovementDirection;
import com.smarthealthfinance.goals.domain.valueobject.GoalMovement;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.enums.TransactionStatus;
import com.smarthealthfinance.transactions.domain.enums.TransactionType;
import com.smarthealthfinance.transactions.domain.model.Transaction;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Classifica uma transação do destino (ADR-0034 §2.3), pela semântica de saldo das Transactions
 * ({@link Transaction#balanceEffectOn}):
 * <ul>
 *   <li>{@code TRANSFER} que entra num destino vindo de fora dele: aporte;</li>
 *   <li>{@code TRANSFER} que sai do destino para fora dele (conta ou cartão): resgate;</li>
 *   <li>{@code TRANSFER} entre duas contas do mesmo destino (reserva com duas {@code SAVINGS}): interna, ignorada;</li>
 *   <li>os demais tipos que mexem no saldo do destino: outro movimento.</li>
 * </ul>
 * Só {@code POSTED} entra; transação que não toca o destino é ignorada.
 */
public final class GoalMovementClassifier {

    private GoalMovementClassifier() {
    }

    public static Optional<GoalMovement> classify(Transaction transaction, Set<AccountId> destination) {
        Objects.requireNonNull(transaction, "transaction");
        Objects.requireNonNull(destination, "destination");
        if (transaction.status() != TransactionStatus.POSTED) {
            return Optional.empty();
        }

        Money effect = Money.zero(transaction.amount().currency());
        for (AccountId account : destination) {
            effect = effect.plus(transaction.balanceEffectOn(account));
        }
        if (effect.amount().signum() == 0) {
            return Optional.empty();
        }
        MovementDirection direction = effect.isPositive() ? MovementDirection.IN : MovementDirection.OUT;
        Money amount = effect.isPositive() ? effect : effect.negate();

        if (transaction.type() != TransactionType.TRANSFER) {
            return Optional.of(new GoalMovement(transaction.id(), GoalMovementKind.OTHER, direction,
                    transaction.occurredOn(), amount, null));
        }
        if (direction == MovementDirection.IN) {
            return Optional.of(new GoalMovement(transaction.id(), GoalMovementKind.CONTRIBUTION, direction,
                    transaction.occurredOn(), amount, transaction.accountId()));
        }
        return Optional.of(new GoalMovement(transaction.id(), GoalMovementKind.WITHDRAWAL, direction,
                transaction.occurredOn(), amount, transaction.destinationAccountId().orElse(null)));
    }
}
