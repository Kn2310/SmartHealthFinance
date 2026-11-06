package com.smarthealthfinance.goals.presentation.dto.request;

import com.smarthealthfinance.shared.presentation.money.MoneyDto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Rascunho do D-CreateGoal (ADR-0036). {@code targetAmount} ausente: na reserva usa o alvo sugerido; nos demais não
 * há cenário. {@code destinationAccountId} só fora da reserva.
 */
public record PreviewGoalRequest(
        @NotBlank @Schema(allowableValues = {"EMERGENCY_RESERVE", "PURCHASE", "CUSTOM"}) String type,
        @Valid MoneyDto targetAmount,
        @NotNull @Valid MoneyDto plannedMonthly,
        @Schema(example = "2027-03") String deadline,
        UUID destinationAccountId) {
}
