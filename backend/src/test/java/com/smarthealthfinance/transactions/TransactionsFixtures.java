package com.smarthealthfinance.transactions;

import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.identity.domain.model.Workspace;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.enums.AdjustmentDirection;
import com.smarthealthfinance.transactions.domain.enums.TransactionStatus;
import com.smarthealthfinance.transactions.domain.enums.TransactionType;
import com.smarthealthfinance.transactions.domain.model.Transaction;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionDescription;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionId;

import java.time.Instant;
import java.time.LocalDate;

import static com.smarthealthfinance.identity.IdentityFixtures.CREATED_AT;

public final class TransactionsFixtures {

    public static final LocalDate SEP_22 = LocalDate.parse("2026-09-22");

    private TransactionsFixtures() {
    }

    public static Money brl(String amount) {
        return Money.of(amount, Workspace.DEFAULT_BASE_CURRENCY);
    }

    public static Transaction expense(WorkspaceId workspaceId, AccountId accountId, String amount, String description) {
        return expense(workspaceId, accountId, amount, SEP_22, description, CREATED_AT);
    }

    public static Transaction expense(WorkspaceId workspaceId, AccountId accountId, String amount, LocalDate occurredOn,
                                      String description, Instant createdAt) {
        return create(workspaceId, TransactionType.EXPENSE, accountId, null, null, amount, occurredOn, description,
                TransactionStatus.POSTED, null, createdAt);
    }

    public static Transaction pendingExpense(WorkspaceId workspaceId, AccountId accountId, String amount,
                                             String description) {
        return create(workspaceId, TransactionType.EXPENSE, accountId, null, null, amount, SEP_22, description,
                TransactionStatus.PENDING, null, CREATED_AT);
    }

    public static Transaction income(WorkspaceId workspaceId, AccountId accountId, String amount, LocalDate occurredOn,
                                     String description) {
        return create(workspaceId, TransactionType.INCOME, accountId, null, null, amount, occurredOn, description,
                TransactionStatus.POSTED, null, CREATED_AT);
    }

    public static Transaction transfer(WorkspaceId workspaceId, AccountId from, AccountId to, String amount) {
        return create(workspaceId, TransactionType.TRANSFER, from, to, null, amount, SEP_22, "Transferência para Reserva",
                TransactionStatus.POSTED, null, CREATED_AT);
    }

    public static Transaction openingBalance(WorkspaceId workspaceId, AccountId accountId, AdjustmentDirection direction,
                                             String amount) {
        return create(workspaceId, TransactionType.ADJUSTMENT, accountId, null, direction, amount, SEP_22,
                "Saldo inicial", TransactionStatus.POSTED, null, CREATED_AT);
    }

    public static Transaction refund(WorkspaceId workspaceId, AccountId accountId, String amount, Transaction original) {
        return create(workspaceId, TransactionType.REFUND, accountId, null, null, amount, SEP_22, "Estorno",
                TransactionStatus.POSTED, original == null ? null : original.id(), CREATED_AT);
    }

    public static Transaction create(WorkspaceId workspaceId, TransactionType type, AccountId accountId,
                                     AccountId destinationAccountId, AdjustmentDirection direction, String amount,
                                     LocalDate occurredOn, String description, TransactionStatus status,
                                     TransactionId refundOf, Instant createdAt) {
        return Transaction.create(TransactionId.generate(createdAt), workspaceId, type, accountId, destinationAccountId,
                direction, brl(amount), occurredOn, new TransactionDescription(description), status, refundOf,
                createdAt);
    }
}
