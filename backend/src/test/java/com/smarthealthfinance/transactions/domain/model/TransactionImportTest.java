package com.smarthealthfinance.transactions.domain.model;

import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.enums.TransactionSource;
import com.smarthealthfinance.transactions.domain.enums.TransactionStatus;
import com.smarthealthfinance.transactions.domain.enums.TransactionType;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionDescription;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.UUID;

import static com.smarthealthfinance.shared.domain.InvalidValueAssert.assertInvalidValue;
import static org.assertj.core.api.Assertions.assertThat;

/** Lançamentos lidos de extrato (ADR-0009 §9/§10). */
class TransactionImportTest {

    private static final Instant NOW = Instant.parse("2026-09-27T12:00:00Z");
    private static final Currency BRL = Currency.getInstance("BRL");
    private static final WorkspaceId WORKSPACE = new WorkspaceId(UUID.randomUUID());
    private static final AccountId ACCOUNT = new AccountId(UUID.randomUUID());

    @Test
    void outflowBecomesPostedImportedExpense() {
        Transaction transaction = imported(false, "86.40");

        assertThat(transaction.type()).isEqualTo(TransactionType.EXPENSE);
        assertThat(transaction.status()).isEqualTo(TransactionStatus.POSTED);
        assertThat(transaction.source()).isEqualTo(TransactionSource.IMPORT);
        assertThat(transaction.balanceEffectOn(ACCOUNT)).isEqualTo(Money.of("-86.40", BRL));
    }

    @Test
    void inflowBecomesPostedImportedIncome() {
        Transaction transaction = imported(true, "4500");

        assertThat(transaction.type()).isEqualTo(TransactionType.INCOME);
        assertThat(transaction.balanceEffectOn(ACCOUNT)).isEqualTo(Money.of("4500", BRL));
    }

    @Test
    void neverInfersTransferOrRefund() {
        Transaction transaction = imported(true, "10");

        assertThat(transaction.destinationAccountId()).isEmpty();
        assertThat(transaction.refundOfTransactionId()).isEmpty();
    }

    @Test
    void keepsTheSameAmountRules() {
        assertInvalidValue(() -> imported(false, "-1"), "amount", "NOT_POSITIVE");
        assertInvalidValue(() -> imported(false, "1.005"), "amount", "TOO_MANY_DECIMALS");
    }

    @Test
    void manualCreationStaysManual() {
        Transaction manual = Transaction.create(TransactionId.generate(NOW), WORKSPACE, TransactionType.EXPENSE,
                ACCOUNT, null, null, Money.of("1", BRL), LocalDate.of(2026, 9, 21), new TransactionDescription("X"),
                TransactionStatus.POSTED, null, NOW);

        assertThat(manual.source()).isEqualTo(TransactionSource.MANUAL);
    }

    private static Transaction imported(boolean inflow, String amount) {
        return Transaction.createImported(TransactionId.generate(NOW), WORKSPACE, ACCOUNT, inflow,
                Money.of(amount, BRL), LocalDate.of(2026, 9, 21), new TransactionDescription("Bistrô Lume"), NOW);
    }
}
