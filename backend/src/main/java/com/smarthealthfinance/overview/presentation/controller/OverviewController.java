package com.smarthealthfinance.overview.presentation.controller;

import com.smarthealthfinance.overview.application.usecase.GetFinancialOverview;
import com.smarthealthfinance.overview.presentation.dto.response.OverviewResponse;
import com.smarthealthfinance.shared.presentation.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/overview")
@Tag(name = "Overview")
public class OverviewController {

    private final GetFinancialOverview getFinancialOverview;

    public OverviewController(GetFinancialOverview getFinancialOverview) {
        this.getFinancialOverview = getFinancialOverview;
    }

    @GetMapping
    @Operation(summary = "Visão financeira consolidada do Workspace: saldo, fluxo de caixa, contas e atividade recente")
    @ApiResponse(responseCode = "200", description = "Overview do período (state indica se há dados)")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (período ou limite inválidos)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND (inexistente ou sem membership)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public OverviewResponse get(
            @PathVariable UUID workspaceId,
            @Parameter(description = "CURRENT_MONTH (padrão, do dia 1º até hoje), PREVIOUS_MONTH ou CUSTOM")
            @RequestParam(required = false) String period,
            @Parameter(description = "Só CUSTOM: primeiro dia, inclusivo (YYYY-MM-DD)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Só CUSTOM: último dia, inclusivo (YYYY-MM-DD); intervalo máximo de 366 dias")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "Movimentações recentes: 1 a 20 (padrão 5)")
            @RequestParam(required = false) Integer recentLimit) {
        return OverviewResponse.from(getFinancialOverview.execute(workspaceId,
                new GetFinancialOverview.Query(period, from, to, recentLimit)));
    }
}
