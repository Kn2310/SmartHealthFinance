package com.smarthealthfinance.goals.presentation.dto.request;

import com.smarthealthfinance.shared.presentation.money.MoneyDto;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionDescription;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

/**
 * "Registrar aporte" (ADR-0034 §2.4, ADR-0035). {@code destinationAccountId} só na reserva (uma conta
 * {@code SAVINGS} ativa); {@code description} ausente assume "Aporte · {nome do objetivo}".
 */
public record GoalContributionRequest(
        @NotNull UUID sourceAccountId,
        UUID destinationAccountId,
        @NotNull @Valid MoneyDto amount,
        @NotNull LocalDate occurredOn,
        @Size(max = TransactionDescription.MAX_LENGTH) String description) {
}
