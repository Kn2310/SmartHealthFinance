package com.smarthealthfinance.goals.presentation.controller;

import com.smarthealthfinance.goals.application.service.GoalSimulator;
import com.smarthealthfinance.goals.application.usecase.GetGoalProjection;
import com.smarthealthfinance.goals.application.usecase.PreviewGoal;
import com.smarthealthfinance.goals.application.usecase.SimulateGoal;
import com.smarthealthfinance.goals.domain.valueobject.GoalPlan;
import com.smarthealthfinance.goals.presentation.dto.request.PreviewGoalRequest;
import com.smarthealthfinance.goals.presentation.dto.request.SimulateGoalRequest;
import com.smarthealthfinance.goals.presentation.dto.response.GoalPreviewResponse;
import com.smarthealthfinance.goals.presentation.dto.response.GoalProjectionResponse;
import com.smarthealthfinance.goals.presentation.dto.response.SimulationResponse;
import com.smarthealthfinance.shared.domain.InvalidValueException;
import com.smarthealthfinance.shared.presentation.error.ApiError;
import com.smarthealthfinance.shared.presentation.money.MoneyDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Simulação, prévia de criação e gráfico dos objetivos (ADR-0034 §4–§5, ADR-0036). Só leitura: nada é gravado; a
 * simulação é sempre {@code SIMULATED} e a projeção separa fato e estimativa.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/goals")
@Tag(name = "Goals")
public class GoalSimulationController {

    private final SimulateGoal simulateGoal;
    private final PreviewGoal previewGoal;
    private final GetGoalProjection projection;

    public GoalSimulationController(SimulateGoal simulateGoal, PreviewGoal previewGoal,
                                    GetGoalProjection projection) {
        this.simulateGoal = simulateGoal;
        this.previewGoal = previewGoal;
        this.projection = projection;
    }

    @PostMapping("/simulations/preview")
    @Operation(operationId = "previewGoal",
            summary = "Prévia simulada do objetivo em criação: já guardado, alvo sugerido da reserva e conclusão")
    @ApiResponse(responseCode = "200", description = "Prévia; nada é gravado")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND ou ACCOUNT_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "ACCOUNT_ARCHIVED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public GoalPreviewResponse preview(@PathVariable UUID workspaceId,
                                       @Valid @RequestBody PreviewGoalRequest request) {
        return GoalPreviewResponse.from(previewGoal.execute(workspaceId, new PreviewGoal.Command(request.type(),
                amount("targetAmount", request.targetAmount()), amount("plannedMonthly", request.plannedMonthly()),
                request.deadline(), request.destinationAccountId())));
    }

    @PostMapping("/{goalId}/simulations")
    @Operation(operationId = "simulateGoal",
            summary = "Simula de 1 a 5 cenários de aporte, prazo ou redução de gasto sobre o objetivo")
    @ApiResponse(responseCode = "200", description = "Cenários simulados; o objetivo não muda")
    @ApiResponse(responseCode = "400",
            description = "VALIDATION_FAILED (scenarios OUT_OF_RANGE, deadline PAST, categoryCode INVALID, ...)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND ou GOAL_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public SimulationResponse simulate(@PathVariable UUID workspaceId, @PathVariable UUID goalId,
                                       @Valid @RequestBody SimulateGoalRequest request) {
        List<GoalSimulator.Scenario> scenarios = new ArrayList<>();
        for (SimulateGoalRequest.SimulationScenarioRequest scenario : request.scenarios()) {
            scenarios.add(new GoalSimulator.Scenario(amount("monthlyAmount", scenario.monthlyAmount()),
                    scenario.deadline(),
                    scenario.reduction() == null ? null : scenario.reduction().categoryCode(),
                    scenario.reduction() == null ? null : amount("reduction.amount", scenario.reduction().amount())));
        }
        return SimulationResponse.from(simulateGoal.execute(workspaceId, goalId, scenarios));
    }

    @GetMapping("/{goalId}/projection")
    @Operation(operationId = "getGoalProjection",
            summary = "Gráfico do objetivo: guardado real (fato), projeção pelo plano (estimado) e prazo")
    @ApiResponse(responseCode = "200", description = "Séries do gráfico")
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND ou GOAL_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public GoalProjectionResponse projection(@PathVariable UUID workspaceId, @PathVariable UUID goalId) {
        return GoalProjectionResponse.from(projection.execute(workspaceId, goalId));
    }

    /** O valor da moeda do Workspace; outra moeda é recusada. */
    private static String amount(String field, MoneyDto money) {
        if (money == null) {
            return null;
        }
        if (!GoalPlan.CURRENCY.getCurrencyCode().equals(money.currency().strip())) {
            throw new InvalidValueException(field + ".currency", "UNSUPPORTED");
        }
        return money.amount();
    }
}
