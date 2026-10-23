package com.smarthealthfinance.cards.presentation.dto.request;

import com.smarthealthfinance.shared.presentation.money.MoneyDto;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionDescription;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Pagamento de fatura, total ou parcial: transferência da conta para o cartão (ADR-0025 §7).
 *
 * @param description ausente = "Pagamento de fatura"
 */
public record CardPaymentRequest(
        @NotNull UUID accountId,
        @NotNull @Valid MoneyDto amount,
        @NotNull LocalDate occurredOn,
        @Size(max = TransactionDescription.MAX_LENGTH) String description) {
}
