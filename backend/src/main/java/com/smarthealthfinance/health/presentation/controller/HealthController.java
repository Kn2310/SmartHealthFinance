package com.smarthealthfinance.health.presentation.controller;

import com.smarthealthfinance.health.application.usecase.GetHealthStatus;
import com.smarthealthfinance.health.application.usecase.HealthQueries;
import com.smarthealthfinance.health.domain.service.HealthCalculator;
import com.smarthealthfinance.health.presentation.dto.response.HealthResponses.HealthEvolutionResponse;
import com.smarthealthfinance.health.presentation.dto.response.HealthResponses.HealthMethodResponse;
import com.smarthealthfinance.health.presentation.dto.response.HealthResponses.HealthResponse;
import com.smarthealthfinance.health.presentation.dto.response.HealthStatusResponse;
import com.smarthealthfinance.shared.presentation.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Financial Health (ADR-0017/0018/0019): leituras do snapshot gravado, sempre com a freshness. O score nunca é
 * calculado na requisição.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/health")
@Tag(name = "Health")
public class HealthController {

    private static final String MONTH = "Mês de referência (YYYY-MM); padrão o mês corrente, parcial até hoje";

    private final GetHealthStatus getStatus;
    private final HealthQueries queries;

    public HealthController(GetHealthStatus getStatus, HealthQueries queries) {
        this.getStatus = getStatus;
        this.queries = queries;
    }

    @GetMapping
    @Operation(summary = "Resumo, dimensões, fatores e próximos passos do mês (READY, INSUFFICIENT_DATA ou NO_DATA)")
    @ApiResponse(responseCode = "200", description = "Health do mês, com freshness")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (month INVALID/FUTURE)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public HealthResponse summary(@PathVariable UUID workspaceId,
                                  @Parameter(description = MONTH) @RequestParam(required = false) String month) {
        return HealthResponse.from(workspaceId, HealthCalculator.ALGORITHM_VERSION,
                queries.summary(workspaceId, month));
    }

    @GetMapping("/evolution")
    @Operation(summary = "Score mês a mês até o mês de referência; meses sem snapshot vêm em NO_DATA")
    @ApiResponse(responseCode = "200", description = "Série mensal com freshness")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (month INVALID/FUTURE, months OUT_OF_RANGE)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public HealthEvolutionResponse evolution(@PathVariable UUID workspaceId,
                                             @Parameter(description = "Último mês da série (YYYY-MM); padrão o "
                                                     + "mês corrente") @RequestParam(required = false) String month,
                                             @Parameter(description = "Meses, 1 a 12 (padrão 6)")
                                             @RequestParam(required = false) Integer months) {
        return HealthEvolutionResponse.from(workspaceId, HealthCalculator.ALGORITHM_VERSION,
                queries.evolution(workspaceId, month, months));
    }

    @GetMapping("/method")
    @Operation(summary = "Como calculamos: dimensões, pesos, curvas, mínimos, janela, fontes, precisão e exclusões")
    @ApiResponse(responseCode = "200", description = "Método da versão atual do algoritmo")
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public HealthMethodResponse method(@PathVariable UUID workspaceId) {
        return HealthMethodResponse.from(queries.method(workspaceId));
    }

    @GetMapping("/status")
    @Operation(summary = "Freshness do snapshot do Health: UP_TO_DATE, RECALCULATING ou NOT_BUILT")
    @ApiResponse(responseCode = "200", description = "Estado do snapshot")
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public HealthStatusResponse status(@PathVariable UUID workspaceId) {
        return HealthStatusResponse.from(getStatus.execute(workspaceId));
    }
}
