package com.smarthealthfinance.transactions.domain.model;

import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.enums.AdjustmentDirection;
import com.smarthealthfinance.transactions.domain.enums.TransactionSource;
import com.smarthealthfinance.transactions.domain.enums.TransactionStatus;
import com.smarthealthfinance.transactions.domain.enums.TransactionType;
import com.smarthealthfinance.transactions.domain.exception.InvalidTransactionStatusTransitionException;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionDescription;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionId;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.Currency;
import java.util.List;

import static com.smarthealthfinance.identity.IdentityFixtures.CREATED_AT;
import static com.smarthealthfinance.shared.domain.InvalidValueAssert.assertInvalidValue;
import static com.smarthealthfinance.transactions.TransactionsFixtures.SEP_22;
import static com.smarthealthfinance.transactions.TransactionsFixtures.brl;
import static com.smarthealthfinance.transactions.TransactionsFixtures.create;
import static com.smarthealthfinance.transactions.TransactionsFixtures.expense;
import static com.smarthealthfinance.transactions.TransactionsFixtures.income;
import static com.smarthealthfinance.transactions.TransactionsFixtures.openingBalance;
import static com.smarthealthfinance.transactions.TransactionsFixtures.pendingExpense;
import static com.smarthealthfinance.transactions.TransactionsFixtures.refund;
import static com.smarthealthfinance.transactions.TransactionsFixtures.transfer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionTest {

    private static final Instant LATER = CREATED_AT.plusSeconds(60);

    private final WorkspaceId workspace = WorkspaceId.generate(CREATED_AT);
    private final AccountId aurora = AccountId.generate(CREATED_AT);
    private final AccountId norte = AccountId.generate(CREATED_AT);

    @Nested
    class Creation {

        @Test
        void createsManualExpense() {
            Transaction transaction = expense(workspace, aurora, "86.40", "Bistrô Lume");

            assertThat(transaction.workspaceId()).isEqualTo(workspace);
            assertThat(transaction.accountId()).isEqualTo(aurora);
            assertThat(transaction.type()).isEqualTo(TransactionType.EXPENSE);
            assertThat(transaction.amount()).isEqualTo(brl("86.40"));
            assertThat(transaction.occurredOn()).isEqualTo(SEP_22);
            assertThat(transaction.description()).isEqualTo(new TransactionDescription("Bistrô Lume"));
            assertThat(transaction.status()).isEqualTo(TransactionStatus.POSTED);
            assertThat(transaction.source()).isEqualTo(TransactionSource.MANUAL);
            assertThat(transaction.destinationAccountId()).isEmpty();
            assertThat(transaction.adjustmentDirection()).isEmpty();
            assertThat(transaction.refundOfTransactionId()).isEmpty();
            assertThat(transaction.createdAt()).isEqualTo(CREATED_AT);
            assertThat(transaction.updatedAt()).isEqualTo(CREATED_AT);
            assertThat(transaction.version()).isZero();
        }

        @Test
        void canStartPending() {
            assertThat(pendingExpense(workspace, aurora, "10", "Agendada").status()).isEqualTo(TransactionStatus.PENDING);
        }

        @ParameterizedTest
        @EnumSource(value = TransactionStatus.class, names = { "CANCELLED", "REVERSED" })
        void cannotStartVoided(TransactionStatus status) {
            assertInvalidValue(() -> create(workspace, TransactionType.EXPENSE, aurora, null, null, "10", SEP_22, "X",
                    status, null, CREATED_AT), "status", "INVALID_INITIAL");
        }

        /** Valores positivos + type (spec 05.4): o sinal nunca vem do valor. */
        @ParameterizedTest
        @ValueSource(strings = { "0", "0.00", "-86.40" })
        void amountMustBePositive(String amount) {
            assertInvalidValue(() -> expense(workspace, aurora, amount, "Bistrô Lume"), "amount", "NOT_POSITIVE");
        }

        @Test
        void amountCannotHaveMoreDecimalsThanTheCurrency() {
            assertInvalidValue(() -> expense(workspace, aurora, "86.405", "Bistrô Lume"), "amount", "TOO_MANY_DECIMALS");
            assertThat(expense(workspace, aurora, "86.4", "Bistrô Lume").amount()).isEqualTo(brl("86.40"));
        }

        @Test
        void currencyIsTheOneOfTheAmount() {
            Transaction transaction = Transaction.create(TransactionId.generate(CREATED_AT), workspace,
                    TransactionType.EXPENSE, aurora, null, null, Money.of("10", Currency.getInstance("USD")), SEP_22,
                    new TransactionDescription("Livro"), TransactionStatus.POSTED, null, CREATED_AT);

            assertThat(transaction.amount().currency().getCurrencyCode()).isEqualTo("USD");
        }

        @Test
        void occurredOnIsRequired() {
            assertInvalidValue(() -> create(workspace, TransactionType.EXPENSE, aurora, null, null, "10", null, "X",
                    TransactionStatus.POSTED, null, CREATED_AT), "occurredOn", "REQUIRED");
        }

        @Test
        void requiresMandatoryFields() {
            assertThatNullPointerException().isThrownBy(() -> create(null, TransactionType.EXPENSE, aurora, null, null,
                    "10", SEP_22, "X", TransactionStatus.POSTED, null, CREATED_AT));
            assertThatNullPointerException().isThrownBy(() -> create(workspace, null, aurora, null, null, "10", SEP_22,
                    "X", TransactionStatus.POSTED, null, CREATED_AT));
            assertThatNullPointerException().isThrownBy(() -> create(workspace, TransactionType.EXPENSE, null, null,
                    null, "10", SEP_22, "X", TransactionStatus.POSTED, null, CREATED_AT));
        }
    }

    @Nested
    class TypeSpecificRules {

        /** Transferência é uma única transação com origem e destino: nunca é despesa nem receita. */
        @Test
        void transferMovesBetweenTwoAccounts() {
            Transaction transaction = transfer(workspace, aurora, norte, "500");

            assertThat(transaction.accountId()).isEqualTo(aurora);
            assertThat(transaction.destinationAccountId()).contains(norte);
            assertThat(transaction.involves(aurora)).isTrue();
            assertThat(transaction.involves(norte)).isTrue();
        }

        @Test
        void transferRequiresDestination() {
            assertInvalidValue(() -> transfer(workspace, aurora, null, "500"), "destinationAccountId", "REQUIRED");
        }

        @Test
        void transferCannotTargetTheSourceAccount() {
            assertInvalidValue(() -> transfer(workspace, aurora, aurora, "500"), "destinationAccountId", "SAME_ACCOUNT");
        }

        @ParameterizedTest
        @EnumSource(value = TransactionType.class, names = "TRANSFER", mode = EnumSource.Mode.EXCLUDE)
        void destinationOnlyForTransfers(TransactionType type) {
            assertInvalidValue(() -> create(workspace, type, aurora, norte, AdjustmentDirection.INCREASE, "10", SEP_22,
                    "X", TransactionStatus.POSTED, null, CREATED_AT), "destinationAccountId", "NOT_ALLOWED");
        }

        /** Saldo inicial negativo (cheque especial) precisa de direção explícita, pois o valor é sempre positivo. */
        @ParameterizedTest
        @EnumSource(AdjustmentDirection.class)
        void adjustmentCarriesItsDirection(AdjustmentDirection direction) {
            assertThat(openingBalance(workspace, aurora, direction, "1200").adjustmentDirection()).contains(direction);
        }

        @Test
        void adjustmentRequiresDirection() {
            assertInvalidValue(() -> openingBalance(workspace, aurora, null, "1200"), "adjustmentDirection", "REQUIRED");
        }

        @ParameterizedTest
        @EnumSource(value = TransactionType.class, names = { "ADJUSTMENT", "TRANSFER" },
                mode = EnumSource.Mode.EXCLUDE)
        void directionOnlyForAdjustments(TransactionType type) {
            assertInvalidValue(() -> create(workspace, type, aurora, null, AdjustmentDirection.DECREASE, "10", SEP_22,
                    "X", TransactionStatus.POSTED, null, CREATED_AT), "adjustmentDirection", "NOT_ALLOWED");
        }

        @Test
        void refundMayBeUnlinked() {
            assertThat(refund(workspace, aurora, "10", null).refundOfTransactionId()).isEmpty();
        }

        @ParameterizedTest
        @EnumSource(value = TransactionType.class, names = { "INCOME", "EXPENSE" })
        void onlyRefundsLinkToAnOriginalTransaction(TransactionType type) {
            assertInvalidValue(() -> create(workspace, type, aurora, null, null, "10", SEP_22, "X",
                    TransactionStatus.POSTED, TransactionId.generate(CREATED_AT), CREATED_AT),
                    "refundOfTransactionId", "NOT_ALLOWED");
        }
    }

    @Nested
    class Refunds {

        private final Transaction original = expense(workspace, aurora, "100.00", "Loja");

        @Test
        void postedExpenseIsRefundableUpToItsAmount() {
            original.ensureRefundable(brl("100.00"), List.of());
        }

        @Test
        void cumulativeRefundsCannotExceedTheOriginal() {
            List<Transaction> previous = List.of(refund(workspace, aurora, "60", original));

            original.ensureRefundable(brl("40"), previous);
            assertInvalidValue(() -> original.ensureRefundable(brl("40.01"), previous), "amount", "EXCEEDS_REFUNDABLE");
        }

        @Test
        void voidedRefundsDoNotCount() {
            Transaction cancelled = pendingRefund();
            cancelled.cancel(LATER);

            original.ensureRefundable(brl("100"), List.of(cancelled));
        }

        @Test
        void pendingRefundsCount() {
            assertInvalidValue(() -> original.ensureRefundable(brl("1"), List.of(pendingRefund())), "amount",
                    "EXCEEDS_REFUNDABLE");
        }

        @Test
        void onlyPostedExpensesAreRefundable() {
            Transaction pending = pendingExpense(workspace, aurora, "100", "Loja");
            Transaction salary = income(workspace, aurora, "100", SEP_22, "Salário");
            Transaction reversed = expense(workspace, aurora, "100", "Loja");
            reversed.reverse(LATER);

            for (Transaction notRefundable : List.of(pending, salary, reversed)) {
                assertInvalidValue(() -> notRefundable.ensureRefundable(brl("1"), List.of()), "refundOfTransactionId",
                        "NOT_REFUNDABLE");
            }
        }

        private Transaction pendingRefund() {
            return create(workspace, TransactionType.REFUND, aurora, null, null, "100", SEP_22, "Estorno",
                    TransactionStatus.PENDING, original.id(), CREATED_AT);
        }
    }

    @Nested
    class StatusLifecycle {

        @Test
        void pendingIsPosted() {
            Transaction transaction = pendingExpense(workspace, aurora, "10", "Agendada");

            transaction.post(LATER);

            assertThat(transaction.status()).isEqualTo(TransactionStatus.POSTED);
            assertThat(transaction.updatedAt()).isEqualTo(LATER);
        }

        @Test
        void pendingIsCancelled() {
            Transaction transaction = pendingExpense(workspace, aurora, "10", "Agendada");

            transaction.cancel(LATER);

            assertThat(transaction.status()).isEqualTo(TransactionStatus.CANCELLED);
            assertThat(transaction.isVoided()).isTrue();
        }

        @Test
        void postedIsReversedNeverCancelled() {
            Transaction transaction = expense(workspace, aurora, "10", "Loja");

            assertThatThrownBy(() -> transaction.cancel(LATER))
                    .isInstanceOf(InvalidTransactionStatusTransitionException.class);

            transaction.reverse(LATER);

            assertThat(transaction.status()).isEqualTo(TransactionStatus.REVERSED);
        }

        @Test
        void pendingCannotBeReversed() {
            Transaction transaction = pendingExpense(workspace, aurora, "10", "Agendada");

            assertThatThrownBy(() -> transaction.reverse(LATER))
                    .isInstanceOf(InvalidTransactionStatusTransitionException.class);
            assertThat(transaction.status()).isEqualTo(TransactionStatus.PENDING);
        }

        @Test
        void voidedTransactionsAreTerminal() {
            Transaction cancelled = pendingExpense(workspace, aurora, "10", "Agendada");
            cancelled.cancel(LATER);
            Transaction reversed = expense(workspace, aurora, "10", "Loja");
            reversed.reverse(LATER);

            assertThatThrownBy(() -> cancelled.post(LATER))
                    .isInstanceOf(InvalidTransactionStatusTransitionException.class);
            assertThatThrownBy(() -> reversed.post(LATER))
                    .isInstanceOf(InvalidTransactionStatusTransitionException.class);
            assertThatThrownBy(() -> reversed.cancel(LATER))
                    .isInstanceOf(InvalidTransactionStatusTransitionException.class);
        }

        /** Repetir a mesma ação é idempotente (retries de rede não falham nem mexem em updatedAt). */
        @Test
        void repeatingTheSameTransitionIsNoOp() {
            Transaction posted = expense(workspace, aurora, "10", "Loja");
            posted.post(LATER);
            assertThat(posted.updatedAt()).isEqualTo(CREATED_AT);

            Transaction reversed = expense(workspace, aurora, "10", "Loja");
            reversed.reverse(LATER);
            reversed.reverse(LATER.plusSeconds(60));
            assertThat(reversed.updatedAt()).isEqualTo(LATER);

            Transaction cancelled = pendingExpense(workspace, aurora, "10", "Agendada");
            cancelled.cancel(LATER);
            cancelled.cancel(LATER.plusSeconds(60));
            assertThat(cancelled.updatedAt()).isEqualTo(LATER);
        }
    }

    @Nested
    class Description {

        @Test
        void descriptionIsEditableWithoutTouchingFinancialFacts() {
            Transaction transaction = expense(workspace, aurora, "86.40", "BISTRO LUME PIRACICABA");

            transaction.updateDescription(new TransactionDescription("Bistrô Lume"), LATER);

            assertThat(transaction.description()).isEqualTo(new TransactionDescription("Bistrô Lume"));
            assertThat(transaction.updatedAt()).isEqualTo(LATER);
            assertThat(transaction.amount()).isEqualTo(brl("86.40"));
            assertThat(transaction.status()).isEqualTo(TransactionStatus.POSTED);
        }

        @Test
        void sameDescriptionKeepsUpdatedAt() {
            Transaction transaction = expense(workspace, aurora, "86.40", "Bistrô Lume");

            transaction.updateDescription(new TransactionDescription(" Bistrô Lume "), LATER);

            assertThat(transaction.updatedAt()).isEqualTo(CREATED_AT);
        }
    }
}
