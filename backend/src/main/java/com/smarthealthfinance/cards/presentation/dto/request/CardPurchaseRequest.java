package com.smarthealthfinance.cards.presentation.dto.request;

import com.smarthealthfinance.shared.presentation.money.MoneyDto;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionDescription;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Compra no cartão (ADR-0026 §10). {@code amount} é o total; as parcelas são calculadas no backend (centavos na 1ª).
 *
 * @param installmentCount 1 a 48; ausente = à vista
 */
public record CardPurchaseRequest(
        @NotNull @Valid MoneyDto amount,
        @NotNull LocalDate occurredOn,
        @NotBlank @Size(max = TransactionDescription.MAX_LENGTH) String description,
        @Schema(minimum = "1", maximum = "48") Integer installmentCount) {
}
