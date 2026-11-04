package com.smarthealthfinance.forecast.presentation.controller;

import com.smarthealthfinance.forecast.application.usecase.GetForecastStatus;
import com.smarthealthfinance.forecast.presentation.dto.response.ForecastStatusResponse;
import com.smarthealthfinance.shared.presentation.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Forecast (ADR-0030/0031). Nesta parte só a freshness do snapshot; projeções e recorrências entram na parte C.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/forecast")
@Tag(name = "Forecast")
public class ForecastController {

    private final GetForecastStatus getStatus;

    public ForecastController(GetForecastStatus getStatus) {
        this.getStatus = getStatus;
    }

    @GetMapping("/status")
    @Operation(summary = "Freshness do snapshot do Forecast: UP_TO_DATE, RECALCULATING ou NOT_BUILT")
    @ApiResponse(responseCode = "200", description = "Estado do snapshot")
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ForecastStatusResponse status(@PathVariable UUID workspaceId) {
        return ForecastStatusResponse.from(getStatus.execute(workspaceId));
    }
}
