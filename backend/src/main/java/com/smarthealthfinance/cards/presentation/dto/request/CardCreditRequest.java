package com.smarthealthfinance.cards.presentation.dto.request;

import com.smarthealthfinance.shared.presentation.money.MoneyDto;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionDescription;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Estorno ou crédito no cartão (ADR-0025 §8).
 *
 * @param refundOfTransactionId compra deste cartão que o estorno devolve; opcional
 */
public record CardCreditRequest(
        @NotNull @Valid MoneyDto amount,
        @NotNull LocalDate occurredOn,
        @NotBlank @Size(max = TransactionDescription.MAX_LENGTH) String description,
        UUID refundOfTransactionId) {
}
