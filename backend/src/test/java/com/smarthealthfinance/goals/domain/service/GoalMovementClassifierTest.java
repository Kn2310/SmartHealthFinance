package com.smarthealthfinance.goals.domain.service;

import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.goals.domain.enums.GoalMovementKind;
import com.smarthealthfinance.goals.domain.enums.MovementDirection;
import com.smarthealthfinance.goals.domain.valueobject.GoalMovement;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.enums.TransactionStatus;
import com.smarthealthfinance.transactions.domain.enums.TransactionType;
import com.smarthealthfinance.transactions.domain.model.Transaction;
import com.smarthealthfinance.transactions.domain.valueobject.CardReference;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionDescription;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Classificação dos movimentos do destino (ADR-0034 §2.3). */
class GoalMovementClassifierTest {

    private static final Currency BRL = Currency.getInstance("BRL");
    private static final Instant NOW = Instant.parse("2026-09-22T15:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 21);
    private static final WorkspaceId WORKSPACE = new WorkspaceId(UUID.randomUUID());
    private static final AccountId AURORA = new AccountId(UUID.randomUUID());
    private static final AccountId NORTE = new AccountId(UUID.randomUUID());
    private static final AccountId NORTE_2 = new AccountId(UUID.randomUUID());

    @Test
    void transferIntoTheDestinationIsAContributionFromTheSource() {
        GoalMovement movement = classify(transfer(AURORA, NORTE, "500.00"), Set.of(NORTE)).orElseThrow();

        assertThat(movement.kind()).isEqualTo(GoalMovementKind.CONTRIBUTION);
        assertThat(movement.direction()).isEqualTo(MovementDirection.IN);
        assertThat(movement.amount()).isEqualTo(Money.of("500.00", BRL));
        assertThat(movement.counterpart()).contains(AURORA);
        assertThat(movement.occurredOn()).isEqualTo(DAY);
    }

    @Test
    void transferOutOfTheDestinationIsAWithdrawal() {
        GoalMovement movement = classify(transfer(NORTE, AURORA, "200.00"), Set.of(NORTE)).orElseThrow();

        assertThat(movement.kind()).isEqualTo(GoalMovementKind.WITHDRAWAL);
        assertThat(movement.direction()).isEqualTo(MovementDirection.OUT);
        assertThat(movement.counterpart()).contains(AURORA);
    }

    @Test
    void cardPaymentFromTheDestinationIsAWithdrawalWithoutCounterpartAccount() {
        Transaction payment = Transaction.createCardPayment(TransactionId.generate(NOW), WORKSPACE, NORTE,
                new CardReference(UUID.randomUUID()), Money.of("100.00", BRL), DAY, new TransactionDescription("Fatura"),
                NOW);

        GoalMovement movement = classify(payment, Set.of(NORTE)).orElseThrow();

        assertThat(movement.kind()).isEqualTo(GoalMovementKind.WITHDRAWAL);
        assertThat(movement.counterpart()).isEmpty();
    }

    @Test
    void transferBetweenTwoReserveAccountsIsInternal() {
        assertThat(classify(transfer(NORTE, NORTE_2, "300.00"), Set.of(NORTE, NORTE_2))).isEmpty();
    }

    @Test
    void otherMovementsOfTheDestinationChangeTheBalanceButAreNotContributions() {
        GoalMovement income = classify(fact(TransactionType.INCOME, NORTE, "12.34", TransactionStatus.POSTED),
                Set.of(NORTE)).orElseThrow();
        GoalMovement expense = classify(fact(TransactionType.EXPENSE, NORTE, "50.00", TransactionStatus.POSTED),
                Set.of(NORTE)).orElseThrow();

        assertThat(income.kind()).isEqualTo(GoalMovementKind.OTHER);
        assertThat(income.direction()).isEqualTo(MovementDirection.IN);
        assertThat(expense.kind()).isEqualTo(GoalMovementKind.OTHER);
        assertThat(expense.direction()).isEqualTo(MovementDirection.OUT);
        assertThat(income.counterpart()).isEmpty();
    }

    @Test
    void pendingAndTransactionsOutsideTheDestinationAreIgnored() {
        assertThat(classify(fact(TransactionType.EXPENSE, NORTE, "50.00", TransactionStatus.PENDING), Set.of(NORTE)))
                .isEmpty();
        assertThat(classify(fact(TransactionType.EXPENSE, AURORA, "50.00", TransactionStatus.POSTED), Set.of(NORTE)))
                .isEmpty();
    }

    private static Optional<GoalMovement> classify(Transaction transaction, Set<AccountId> destination) {
        return GoalMovementClassifier.classify(transaction, destination);
    }

    private static Transaction transfer(AccountId from, AccountId to, String amount) {
        return Transaction.create(TransactionId.generate(NOW), WORKSPACE, TransactionType.TRANSFER, from, to, null,
                Money.of(amount, BRL), DAY, new TransactionDescription("Aporte"), TransactionStatus.POSTED, null, NOW);
    }

    private static Transaction fact(TransactionType type, AccountId account, String amount, TransactionStatus status) {
        return Transaction.create(TransactionId.generate(NOW), WORKSPACE, type, account, null, null,
                Money.of(amount, BRL), DAY, new TransactionDescription("Movimento"), status, null, NOW);
    }
}
