package com.smarthealthfinance.transactions.domain.model;

import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.transactions.domain.enums.AdjustmentDirection;
import com.smarthealthfinance.transactions.domain.enums.BalanceEffect;
import com.smarthealthfinance.transactions.domain.enums.TransactionStatus;
import com.smarthealthfinance.transactions.domain.enums.TransactionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static com.smarthealthfinance.identity.IdentityFixtures.CREATED_AT;
import static com.smarthealthfinance.shared.domain.InvalidValueAssert.assertInvalidValue;
import static com.smarthealthfinance.transactions.TransactionsFixtures.brl;
import static com.smarthealthfinance.transactions.TransactionsFixtures.expense;
import static com.smarthealthfinance.transactions.TransactionsFixtures.income;
import static com.smarthealthfinance.transactions.TransactionsFixtures.openingBalance;
import static com.smarthealthfinance.transactions.TransactionsFixtures.pendingExpense;
import static com.smarthealthfinance.transactions.TransactionsFixtures.refund;
import static com.smarthealthfinance.transactions.TransactionsFixtures.SEP_22;
import static com.smarthealthfinance.transactions.TransactionsFixtures.transfer;
import static org.assertj.core.api.Assertions.assertThat;

/** Fonte única da semântica de saldo (ADR-0006): o Overview e o adapter SQL se alinham a estes casos. */
class TransactionBalanceEffectTest {

    private final WorkspaceId workspace = WorkspaceId.generate(CREATED_AT);
    private final AccountId aurora = AccountId.generate(CREATED_AT);
    private final AccountId norte = AccountId.generate(CREATED_AT);

    @Test
    void incomeCreditsAndExpenseDebits() {
        assertThat(income(workspace, aurora, "100", SEP_22, "Salário").balanceEffectOn(aurora)).isEqualTo(brl("100"));
        assertThat(expense(workspace, aurora, "86.40", "Bistrô").balanceEffectOn(aurora)).isEqualTo(brl("-86.40"));
    }

    @Test
    void refundCredits() {
        assertThat(refund(workspace, aurora, "30", null).balanceEffectOn(aurora)).isEqualTo(brl("30"));
    }

    @Test
    void adjustmentFollowsItsDirection() {
        assertThat(openingBalance(workspace, aurora, AdjustmentDirection.INCREASE, "500").balanceEffectOn(aurora))
                .isEqualTo(brl("500"));
        assertThat(openingBalance(workspace, aurora, AdjustmentDirection.DECREASE, "500").balanceEffectOn(aurora))
                .isEqualTo(brl("-500"));
    }

    @Test
    void transferDebitsOriginAndCreditsDestinationNettingToZero() {
        Transaction transfer = transfer(workspace, aurora, norte, "250");

        assertThat(transfer.balanceEffectOn(aurora)).isEqualTo(brl("-250"));
        assertThat(transfer.balanceEffectOn(norte)).isEqualTo(brl("250"));
        assertThat(transfer.balanceEffectOn(aurora).plus(transfer.balanceEffectOn(norte))).isEqualTo(brl("0"));
    }

    @Test
    void unrelatedAccountIsUnaffected() {
        AccountId other = AccountId.generate(CREATED_AT);

        assertThat(transfer(workspace, aurora, norte, "250").balanceEffectOn(other)).isEqualTo(brl("0"));
        assertThat(expense(workspace, aurora, "10", "x").balanceEffectOn(norte)).isEqualTo(brl("0"));
    }

    @Test
    void pendingDoesNotMoveBalance() {
        assertThat(pendingExpense(workspace, aurora, "86.40", "Bistrô").balanceEffectOn(aurora)).isEqualTo(brl("0"));
    }

    @Test
    void cancelledAndReversedDoNotMoveBalance() {
        Transaction cancelled = pendingExpense(workspace, aurora, "86.40", "Bistrô");
        cancelled.cancel(CREATED_AT);
        Transaction reversed = expense(workspace, aurora, "86.40", "Bistrô");
        reversed.reverse(CREATED_AT);

        assertThat(cancelled.balanceEffectOn(aurora)).isEqualTo(brl("0"));
        assertThat(reversed.balanceEffectOn(aurora)).isEqualTo(brl("0"));
    }

    @Test
    void postingAPendingTransactionStartsMovingBalance() {
        Transaction pending = pendingExpense(workspace, aurora, "40", "Mercado");
        pending.post(CREATED_AT);

        assertThat(pending.balanceEffectOn(aurora)).isEqualTo(brl("-40"));
    }

    @ParameterizedTest
    @EnumSource(TransactionStatus.class)
    void onlyPostedAffectsBalance(TransactionStatus status) {
        assertThat(status.affectsBalance()).isEqualTo(status == TransactionStatus.POSTED);
    }

    @Test
    void originEffectPerType() {
        assertThat(TransactionType.INCOME.originEffect(null)).isEqualTo(BalanceEffect.CREDIT);
        assertThat(TransactionType.REFUND.originEffect(null)).isEqualTo(BalanceEffect.CREDIT);
        assertThat(TransactionType.EXPENSE.originEffect(null)).isEqualTo(BalanceEffect.DEBIT);
        assertThat(TransactionType.TRANSFER.originEffect(null)).isEqualTo(BalanceEffect.DEBIT);
        assertThat(TransactionType.ADJUSTMENT.originEffect(AdjustmentDirection.INCREASE)).isEqualTo(BalanceEffect.CREDIT);
        assertThat(TransactionType.ADJUSTMENT.originEffect(AdjustmentDirection.DECREASE)).isEqualTo(BalanceEffect.DEBIT);
    }

    @Test
    void adjustmentWithoutDirectionIsRejected() {
        assertInvalidValue(() -> TransactionType.ADJUSTMENT.originEffect(null), "adjustmentDirection", "REQUIRED");
    }
}
