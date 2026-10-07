package com.smarthealthfinance.transactions.presentation.dto.request;

import com.smarthealthfinance.transactions.domain.valueobject.TransactionDescription;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Só campos não financeiros são editáveis (ADR-0005). */
public record UpdateTransactionRequest(
        @NotBlank @Size(max = TransactionDescription.MAX_LENGTH) String description) {
}
