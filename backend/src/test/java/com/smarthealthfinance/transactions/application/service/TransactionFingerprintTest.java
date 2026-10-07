package com.smarthealthfinance.transactions.application.service;

import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.transactions.domain.enums.AdjustmentDirection;
import com.smarthealthfinance.transactions.domain.enums.TransactionStatus;
import com.smarthealthfinance.transactions.domain.enums.TransactionType;
import com.smarthealthfinance.transactions.domain.model.Transaction;
import org.junit.jupiter.api.Test;

import static com.smarthealthfinance.identity.IdentityFixtures.CREATED_AT;
import static com.smarthealthfinance.transactions.TransactionsFixtures.SEP_22;
import static com.smarthealthfinance.transactions.TransactionsFixtures.create;
import static com.smarthealthfinance.transactions.TransactionsFixtures.expense;
import static com.smarthealthfinance.transactions.TransactionsFixtures.openingBalance;
import static org.assertj.core.api.Assertions.assertThat;

class TransactionFingerprintTest {

    private final WorkspaceId workspace = WorkspaceId.generate(CREATED_AT);
    private final AccountId aurora = AccountId.generate(CREATED_AT);

    @Test
    void isSha256Hex() {
        assertThat(TransactionFingerprint.of(expense(workspace, aurora, "86.40", "Bistrô Lume")))
                .hasSize(64).matches("[0-9a-f]{64}");
    }

    /** Id e timestamps são gerados a cada tentativa: não podem fazer parte da intenção. */
    @Test
    void ignoresGeneratedIdsAndTimestamps() {
        Transaction first = expense(workspace, aurora, "86.40", SEP_22, "Bistrô Lume", CREATED_AT);
        Transaction retry = expense(workspace, aurora, "86.4", SEP_22, "Bistrô Lume", CREATED_AT.plusSeconds(30));

        assertThat(TransactionFingerprint.of(retry)).isEqualTo(TransactionFingerprint.of(first));
    }

    @Test
    void changesWithAnyBusinessField() {
        String base = TransactionFingerprint.of(expense(workspace, aurora, "86.40", "Bistrô Lume"));

        assertThat(TransactionFingerprint.of(expense(workspace, aurora, "86.41", "Bistrô Lume"))).isNotEqualTo(base);
        assertThat(TransactionFingerprint.of(expense(workspace, aurora, "86.40", "Bistro Lume"))).isNotEqualTo(base);
        assertThat(TransactionFingerprint.of(expense(workspace, AccountId.generate(CREATED_AT), "86.40", "Bistrô Lume")))
                .isNotEqualTo(base);
        assertThat(TransactionFingerprint.of(expense(workspace, aurora, "86.40", SEP_22.plusDays(1), "Bistrô Lume",
                CREATED_AT))).isNotEqualTo(base);
        assertThat(TransactionFingerprint.of(create(workspace, TransactionType.EXPENSE, aurora, null, null, "86.40",
                SEP_22, "Bistrô Lume", TransactionStatus.PENDING, null, CREATED_AT))).isNotEqualTo(base);
        assertThat(TransactionFingerprint.of(create(workspace, TransactionType.INCOME, aurora, null, null, "86.40",
                SEP_22, "Bistrô Lume", TransactionStatus.POSTED, null, CREATED_AT))).isNotEqualTo(base);
    }

    @Test
    void distinguishesAdjustmentDirection() {
        assertThat(TransactionFingerprint.of(openingBalance(workspace, aurora, AdjustmentDirection.INCREASE, "10")))
                .isNotEqualTo(TransactionFingerprint.of(openingBalance(workspace, aurora, AdjustmentDirection.DECREASE,
                        "10")));
    }

    /** Separadores no texto livre não podem forjar colisão entre campos. */
    @Test
    void fieldBoundariesAreUnambiguous() {
        assertThat(TransactionFingerprint.of(expense(workspace, aurora, "10", "a|b")))
                .isNotEqualTo(TransactionFingerprint.of(expense(workspace, aurora, "10", "a")));
    }
}
