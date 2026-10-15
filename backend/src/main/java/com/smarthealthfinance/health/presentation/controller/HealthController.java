package com.smarthealthfinance.health.presentation.controller;

import com.smarthealthfinance.health.application.usecase.GetHealthStatus;
import com.smarthealthfinance.health.presentation.dto.response.HealthStatusResponse;
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

/** Financial Health (ADR-0017/0018). Nesta parte, só a freshness do snapshot; as consultas chegam na parte D. */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/health")
@Tag(name = "Health")
public class HealthController {

    private final GetHealthStatus getStatus;

    public HealthController(GetHealthStatus getStatus) {
        this.getStatus = getStatus;
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
