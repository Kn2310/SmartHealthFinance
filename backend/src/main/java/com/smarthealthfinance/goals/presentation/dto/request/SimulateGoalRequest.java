package com.smarthealthfinance.goals.presentation.dto.request;

import com.smarthealthfinance.shared.presentation.money.MoneyDto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** De 1 a 5 cenários (ADR-0036). Campos ausentes usam o plano do objetivo. */
public record SimulateGoalRequest(@NotEmpty @Size(max = 5) List<@Valid @NotNull SimulationScenarioRequest> scenarios) {

    /**
     * @param monthlyAmount outro aporte mensal
     * @param deadline      outro prazo ({@code "2027-07"}); só ele = o aporte necessário para o prazo
     * @param reduction     redução de gasto numa categoria, somada ao aporte
     */
    public record SimulationScenarioRequest(@Valid MoneyDto monthlyAmount,
                                            @Schema(example = "2027-07") String deadline,
                                            @Valid SimulationReductionRequest reduction) {}

    public record SimulationReductionRequest(@NotBlank String categoryCode, @NotNull @Valid MoneyDto amount) {}
}
