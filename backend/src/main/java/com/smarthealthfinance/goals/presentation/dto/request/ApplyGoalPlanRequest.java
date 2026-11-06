package com.smarthealthfinance.goals.presentation.dto.request;

import com.smarthealthfinance.shared.presentation.money.MoneyDto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** "Aplicar ao objetivo" (ADR-0034 §5): o novo aporte planejado e o prazo ({@code "2027-07"} ou ausente). */
public record ApplyGoalPlanRequest(
        @NotNull @Valid MoneyDto plannedMonthly,
        @Schema(example = "2027-07") String deadline) {
}
