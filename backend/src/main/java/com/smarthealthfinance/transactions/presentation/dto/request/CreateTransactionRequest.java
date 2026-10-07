package com.smarthealthfinance.transactions.presentation.dto.request;

import com.smarthealthfinance.shared.presentation.money.MoneyDto;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionDescription;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Lançamento manual. {@code type}, {@code status}, {@code adjustmentDirection} e as regras por tipo
 * são validados pelo domínio; aqui só a forma do payload.
 *
 * @param destinationAccountId  somente TRANSFER
 * @param adjustmentDirection   somente ADJUSTMENT (INCREASE | DECREASE)
 * @param status                PENDING | POSTED (padrão POSTED)
 * @param refundOfTransactionId somente REFUND, opcional
 */
public record CreateTransactionRequest(
        @NotBlank String type,
        @NotNull UUID accountId,
        UUID destinationAccountId,
        String adjustmentDirection,
        @NotNull @Valid MoneyDto amount,
        @NotNull LocalDate occurredOn,
        @NotBlank @Size(max = TransactionDescription.MAX_LENGTH) String description,
        String status,
        UUID refundOfTransactionId) {
}
