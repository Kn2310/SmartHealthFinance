package com.smarthealthfinance.analytics.presentation.controller;

import com.smarthealthfinance.analytics.application.usecase.GetAnalyticsStatus;
import com.smarthealthfinance.analytics.presentation.dto.response.AnalyticsStatusResponse;
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

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/analytics")
@Tag(name = "Analytics")
public class AnalyticsController {

    private final GetAnalyticsStatus getStatus;

    public AnalyticsController(GetAnalyticsStatus getStatus) {
        this.getStatus = getStatus;
    }

    @GetMapping("/status")
    @Operation(summary = "Freshness da projeção de Analytics: UP_TO_DATE, UPDATING (eventos pendentes) ou NOT_BUILT")
    @ApiResponse(responseCode = "200", description = "Estado da projeção")
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public AnalyticsStatusResponse status(@PathVariable UUID workspaceId) {
        return AnalyticsStatusResponse.from(getStatus.execute(workspaceId));
    }
}
