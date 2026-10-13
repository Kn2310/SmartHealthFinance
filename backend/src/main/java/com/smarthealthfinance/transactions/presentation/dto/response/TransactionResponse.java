package com.smarthealthfinance.transactions.presentation.dto.response;

import com.smarthealthfinance.shared.presentation.money.MoneyDto;
import com.smarthealthfinance.transactions.application.dto.TransactionView;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** @param categoryCode {@code null} = "Sem categoria"; o nome vem do catálogo ({@code GET .../categories}) */
public record TransactionResponse(UUID id, UUID workspaceId, UUID accountId, UUID destinationAccountId, String type,
        String adjustmentDirection, MoneyDto amount, LocalDate occurredOn, String description, String status,
        String source, UUID refundOfTransactionId, String categoryCode, Instant createdAt, Instant updatedAt) {

    public static TransactionResponse from(TransactionView view) {
        return new TransactionResponse(view.id(), view.workspaceId(), view.accountId(), view.destinationAccountId(),
                view.type().name(), view.adjustmentDirection() == null ? null : view.adjustmentDirection().name(),
                MoneyDto.of(view.amount(), view.currency()), view.occurredOn(), view.description(),
                view.status().name(), view.source().name(), view.refundOfTransactionId(), view.categoryCode(),
                view.createdAt(), view.updatedAt());
    }
}
