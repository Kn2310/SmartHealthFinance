package com.smarthealthfinance.goals.presentation.dto.request;

import com.smarthealthfinance.goals.domain.valueobject.GoalName;
import com.smarthealthfinance.shared.presentation.money.MoneyDto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Criação de objetivo (ADR-0035). {@code destinationAccountId} é obrigatório fora da reserva e proibido nela;
 * {@code origin} ausente assume {@code MANUAL}; {@code deadline} no formato {@code "2027-07"} ou ausente (sem prazo).
 * Tipos e regras são validados pelo domínio.
 */
public record CreateGoalRequest(
        @NotBlank @Size(max = GoalName.MAX_LENGTH) String name,
        @NotBlank @Schema(allowableValues = {"EMERGENCY_RESERVE", "PURCHASE", "CUSTOM"}) String type,
        @NotNull @Valid MoneyDto targetAmount,
        @NotNull @Valid MoneyDto plannedMonthly,
        @Schema(example = "2027-07") String deadline,
        UUID sourceAccountId,
        UUID destinationAccountId,
        @Valid GoalOriginRequest origin) {
}
