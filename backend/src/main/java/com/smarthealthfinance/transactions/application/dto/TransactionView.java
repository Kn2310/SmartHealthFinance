package com.smarthealthfinance.transactions.application.dto;

import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.transactions.domain.enums.AdjustmentDirection;
import com.smarthealthfinance.transactions.domain.enums.TransactionSource;
import com.smarthealthfinance.transactions.domain.enums.TransactionStatus;
import com.smarthealthfinance.transactions.domain.enums.TransactionType;
import com.smarthealthfinance.transactions.domain.model.Transaction;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionId;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** @param amount já na escala de exibição da moeda (86.40, nunca 86.4000) */
public record TransactionView(
        UUID id, UUID workspaceId, UUID accountId, UUID destinationAccountId, TransactionType type,
        AdjustmentDirection adjustmentDirection, BigDecimal amount, String currency, LocalDate occurredOn,
        String description, TransactionStatus status, TransactionSource source, UUID refundOfTransactionId,
        Instant createdAt, Instant updatedAt
) {
    public static TransactionView from(Transaction transaction) {
        return new TransactionView(
                transaction.id().value(), transaction.workspaceId().value(), transaction.accountId().value(),
                transaction.destinationAccountId().map(AccountId::value).orElse(null), transaction.type(),
                transaction.adjustmentDirection().orElse(null), new BigDecimal(transaction.amount().toPlainString()),
                transaction.amount().currency().getCurrencyCode(), transaction.occurredOn(),
                transaction.description().value(), transaction.status(), transaction.source(),
                transaction.refundOfTransactionId().map(TransactionId::value).orElse(null),
                transaction.createdAt(), transaction.updatedAt()
        );
    }
}
