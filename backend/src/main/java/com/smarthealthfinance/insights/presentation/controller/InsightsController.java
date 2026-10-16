package com.smarthealthfinance.insights.presentation.controller;

import com.smarthealthfinance.insights.application.usecase.GetInsightsStatus;
import com.smarthealthfinance.insights.presentation.dto.response.InsightsStatusResponse;
import com.smarthealthfinance.shared.presentation.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Insights (ADR-0021/0022). Nesta parte, só a freshness da geração; lista, detalhe e transições chegam com a API
 * (parte C).
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/insights")
@Tag(name = "Insights")
public class InsightsController {

    private final GetInsightsStatus getStatus;

    public InsightsController(GetInsightsStatus getStatus) {
        this.getStatus = getStatus;
    }

    @GetMapping("/status")
    @Operation(operationId = "insightsStatus",
            summary = "Freshness dos insights: UP_TO_DATE, UPDATING ou NOT_BUILT")
    @ApiResponse(responseCode = "200", description = "Estado da geração")
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public InsightsStatusResponse status(@PathVariable UUID workspaceId) {
        return InsightsStatusResponse.from(getStatus.execute(workspaceId));
    }
}
