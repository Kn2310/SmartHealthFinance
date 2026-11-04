package com.smarthealthfinance.forecast.presentation.controller;

import com.smarthealthfinance.forecast.application.usecase.RecurringSeriesService;
import com.smarthealthfinance.forecast.domain.enums.SeriesDirection;
import com.smarthealthfinance.forecast.presentation.dto.request.ForecastAdjustmentRequest;
import com.smarthealthfinance.forecast.presentation.dto.response.ForecastRecurringResponses.ForecastRecurringActionResponse;
import com.smarthealthfinance.forecast.presentation.dto.response.ForecastRecurringResponses.ForecastRecurringListResponse;
import com.smarthealthfinance.forecast.presentation.dto.response.ForecastRecurringResponses.ForecastRecurringSeriesDto;
import com.smarthealthfinance.shared.presentation.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Despesas recorrentes (ADR-0032 §2). Mesmo schema e mesmas ações de `/recurring-incomes`. As ações são idempotentes, sem Idempotency-Key, e
 * respondem 200 com a série e `forecastState: RECALCULATING` (o recálculo é assíncrono, ADR-0031 §5).
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/recurring-expenses")
@Tag(name = "Forecast")
public class RecurringExpensesController {

    private static final String NOT_FOUND = "WORKSPACE_NOT_FOUND ou RECURRING_SERIES_NOT_FOUND (inexistente, de outro "
            + "Workspace ou da outra direção)";
    private static final SeriesDirection DIRECTION = SeriesDirection.EXPENSE;

    private final RecurringSeriesService service;

    public RecurringExpensesController(RecurringSeriesService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(operationId = "listRecurringExpenses", summary = "Séries da direção, sem paginação: elegíveis primeiro, depois o maior valor esperado")
    @ApiResponse(responseCode = "200", description = "Séries recorrentes")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (status INVALID)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ForecastRecurringListResponse list(@PathVariable UUID workspaceId,
                                              @Parameter(description = "DETECTED, CONFIRMED ou IGNORED; omitido = todas")
                                              @RequestParam(required = false) String status) {
        return ForecastRecurringListResponse.of(service.list(workspaceId, DIRECTION, status));
    }

    @GetMapping("/{seriesId}")
    @Operation(operationId = "getRecurringExpenseSeries", summary = "Detalhe da série")
    @ApiResponse(responseCode = "200", description = "Série recorrente")
    @ApiResponse(responseCode = "404", description = NOT_FOUND,
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ForecastRecurringSeriesDto detail(@PathVariable UUID workspaceId, @PathVariable UUID seriesId) {
        return ForecastRecurringSeriesDto.of(service.detail(workspaceId, DIRECTION, seriesId));
    }

    @PostMapping("/{seriesId}/confirm")
    @Operation(operationId = "confirmRecurringExpenseSeries", summary = "Confirma a série (CONFIRMED); também desfaz o ignorar. Idempotente")
    @ApiResponse(responseCode = "200", description = "Série confirmada; o forecast recalcula em seguida")
    @ApiResponse(responseCode = "404", description = NOT_FOUND,
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ForecastRecurringActionResponse confirm(@PathVariable UUID workspaceId, @PathVariable UUID seriesId) {
        return ForecastRecurringActionResponse.of(service.confirm(workspaceId, DIRECTION, seriesId));
    }

    @PostMapping("/{seriesId}/ignore")
    @Operation(operationId = "ignoreRecurringExpenseSeries", summary = "Ignora a série (IGNORED); ela não volta a ser sugerida. Idempotente")
    @ApiResponse(responseCode = "200", description = "Série ignorada; o forecast recalcula em seguida")
    @ApiResponse(responseCode = "404", description = NOT_FOUND,
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ForecastRecurringActionResponse ignore(@PathVariable UUID workspaceId, @PathVariable UUID seriesId) {
        return ForecastRecurringActionResponse.of(service.ignore(workspaceId, DIRECTION, seriesId));
    }

    @PutMapping("/{seriesId}/adjustment")
    @Operation(operationId = "adjustRecurringExpenseSeries",
            summary = "Define o valor e/ou o dia esperados do usuário (não mudam a elegibilidade). Idempotente")
    @ApiResponse(responseCode = "200", description = "Série ajustada; o forecast recalcula em seguida")
    @ApiResponse(responseCode = "400",
            description = "VALIDATION_FAILED (corpo vazio; expectedDay fora de 1-31; expectedAmount <= 0 ou com mais de 2 casas)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = NOT_FOUND,
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ForecastRecurringActionResponse adjust(@PathVariable UUID workspaceId, @PathVariable UUID seriesId,
                                                  @RequestBody ForecastAdjustmentRequest request) {
        return ForecastRecurringActionResponse.of(service.adjust(workspaceId, DIRECTION, seriesId,
                () -> AdjustmentMapper.toEdit(request)));
    }

    @DeleteMapping("/{seriesId}/adjustment")
    @Operation(operationId = "clearRecurringExpenseSeriesAdjustment", summary = "Remove o ajuste: voltam os valores detectados. Idempotente")
    @ApiResponse(responseCode = "200", description = "Ajuste removido; o forecast recalcula em seguida")
    @ApiResponse(responseCode = "404", description = NOT_FOUND,
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ForecastRecurringActionResponse clearAdjustment(@PathVariable UUID workspaceId,
                                                           @PathVariable UUID seriesId) {
        return ForecastRecurringActionResponse.of(service.clearAdjustment(workspaceId, DIRECTION, seriesId));
    }
}
