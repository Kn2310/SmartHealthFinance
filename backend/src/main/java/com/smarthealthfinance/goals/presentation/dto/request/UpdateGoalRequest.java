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
 * "Editar objetivo" (ADR-0035): substitui todos os campos editáveis. O tipo e a origem não mudam. Campos opcionais
 * ausentes ficam nulos (sem prazo, sem conta de origem).
 */
public record UpdateGoalRequest(
        @NotBlank @Size(max = GoalName.MAX_LENGTH) String name,
        @NotNull @Valid MoneyDto targetAmount,
        @NotNull @Valid MoneyDto plannedMonthly,
        @Schema(example = "2027-07") String deadline,
        UUID sourceAccountId,
        UUID destinationAccountId) {
}
