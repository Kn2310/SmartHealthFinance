package com.smarthealthfinance.transactions.domain.model;

import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.enums.CashFlowRole;
import com.smarthealthfinance.transactions.domain.enums.TransactionSource;
import com.smarthealthfinance.transactions.domain.enums.TransactionStatus;
import com.smarthealthfinance.transactions.domain.enums.TransactionType;
import com.smarthealthfinance.transactions.domain.exception.InvalidTransactionStatusTransitionException;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static com.smarthealthfinance.shared.domain.InvalidValueAssert.assertInvalidValue;
import static com.smarthealthfinance.transactions.TransactionsFixtures.SEP_22;
import static com.smarthealthfinance.transactions.TransactionsFixtures.brl;
import static com.smarthealthfinance.transactions.TransactionsFixtures.expense;
import static com.smarthealthfinance.transactions.TransactionsFixtures.importedIncome;
import static com.smarthealthfinance.transactions.TransactionsFixtures.income;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** "Marcar como reembolso": estorno do extrato vira REFUND sem mudar o saldo (adendo de 2026-10-14). */
class TransactionReclassificationTest {

    private static final Instant NOW = Instant.parse("2026-09-27T12:00:00Z");
    private static final WorkspaceId WORKSPACE = new WorkspaceId(UUID.randomUUID());
    private static final AccountId ACCOUNT = new AccountId(UUID.randomUUID());

    private final Transaction income = importedIncome(WORKSPACE, ACCOUNT, "129.90", "Estorno compra online - Loja Azul");

    @Test
    void refundCopiesTheStatementFact() {
        Transaction refund = Transaction.refundReplacing(TransactionId.generate(NOW), income, null, NOW);

        assertThat(refund.type()).isEqualTo(TransactionType.REFUND);
        assertThat(refund.status()).isEqualTo(TransactionStatus.POSTED);
        assertThat(refund.source()).isEqualTo(TransactionSource.IMPORT);
        assertThat(refund.accountId()).isEqualTo(ACCOUNT);
        assertThat(refund.amount()).isEqualTo(brl("129.90"));
        assertThat(refund.occurredOn()).isEqualTo(SEP_22);
        assertThat(refund.description()).isEqualTo(income.description());
        assertThat(refund.reclassifiedFromTransactionId()).contains(income.id());
        assertThat(refund.refundOfTransactionId()).isEmpty();
        assertThat(refund.merchant()).isPresent();
    }

    @Test
    void refundMayPointToTheOriginalExpense() {
        Transaction purchase = expense(WORKSPACE, ACCOUNT, "129.90", "Loja Azul");

        Transaction refund = Transaction.refundReplacing(TransactionId.generate(NOW), income, purchase.id(), NOW);

        assertThat(refund.refundOfTransactionId()).contains(purchase.id());
    }

    @Test
    void incomeIsReversedAndPointsToItsReplacement() {
        Transaction refund = Transaction.refundReplacing(TransactionId.generate(NOW), income, null, NOW);

        income.reclassifyAsRefund(refund, NOW);

        assertThat(income.status()).isEqualTo(TransactionStatus.REVERSED);
        assertThat(income.reclassifiedAsTransactionId()).contains(refund.id());
        assertThat(income.updatedAt()).isEqualTo(NOW);
    }

    /** O saldo não muda; no fluxo de caixa a entrada passa de receita a reembolso (ADR-0006 §9). */
    @Test
    void balanceIsUnchangedAndTheInflowMovesFromIncomeToRefunds() {
        Money before = income.balanceEffectOn(ACCOUNT);
        Transaction refund = Transaction.refundReplacing(TransactionId.generate(NOW), income, null, NOW);
        income.reclassifyAsRefund(refund, NOW);

        assertThat(income.balanceEffectOn(ACCOUNT).plus(refund.balanceEffectOn(ACCOUNT))).isEqualTo(before);
        assertThat(income.cashFlowRole()).isEqualTo(CashFlowRole.NONE);
        assertThat(refund.cashFlowRole()).isEqualTo(CashFlowRole.REFUND);
    }

    @Test
    void onlyImportedIncomeIsReclassifiable() {
        Transaction manualIncome = income(WORKSPACE, ACCOUNT, "100", SEP_22, "Estorno lançado à mão");
        Transaction purchase = expense(WORKSPACE, ACCOUNT, "100", "Loja");

        assertInvalidValue(() -> Transaction.refundReplacing(TransactionId.generate(NOW), manualIncome, null, NOW),
                "transactionId", "NOT_RECLASSIFIABLE");
        assertInvalidValue(() -> Transaction.refundReplacing(TransactionId.generate(NOW), purchase, null, NOW),
                "transactionId", "NOT_RECLASSIFIABLE");
    }

    @Test
    void reversedIncomeCannotBeReclassified() {
        income.reverse(NOW);

        assertThatThrownBy(() -> Transaction.refundReplacing(TransactionId.generate(NOW), income, null, NOW))
                .isInstanceOf(InvalidTransactionStatusTransitionException.class);
    }

    @Test
    void incomeOnlyAcceptsItsOwnReplacement() {
        Transaction other = importedIncome(WORKSPACE, ACCOUNT, "10", "Estorno");
        Transaction refundOfOther = Transaction.refundReplacing(TransactionId.generate(NOW), other, null, NOW);

        assertThatThrownBy(() -> income.reclassifyAsRefund(refundOfOther, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(income.status()).isEqualTo(TransactionStatus.POSTED);
    }
}
