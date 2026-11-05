package com.smarthealthfinance.forecast.presentation.controller;

import com.smarthealthfinance.forecast.application.usecase.ForecastQueries;
import com.smarthealthfinance.forecast.application.usecase.GetForecastStatus;
import com.smarthealthfinance.forecast.presentation.dto.response.ForecastResponses.ForecastCashFlowResponse;
import com.smarthealthfinance.forecast.presentation.dto.response.ForecastResponses.ForecastInvoicesResponse;
import com.smarthealthfinance.forecast.presentation.dto.response.ForecastResponses.ForecastMonthResponse;
import com.smarthealthfinance.forecast.presentation.dto.response.ForecastResponses.ForecastNextIncomeResponse;
import com.smarthealthfinance.forecast.presentation.dto.response.ForecastResponses.ForecastUpcomingResponse;
import com.smarthealthfinance.forecast.presentation.dto.response.ForecastStatusResponse;
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
 * Forecast (ADR-0030/0031/0032): uma leitura por bloco, só do snapshot gravado, sempre 200 com {@code state} e
 * {@code freshness}. Nada é calculado na requisição.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/forecast")
@Tag(name = "Forecast")
public class ForecastController {

    private final GetForecastStatus getStatus;
    private final ForecastQueries queries;

    public ForecastController(GetForecastStatus getStatus, ForecastQueries queries) {
        this.getStatus = getStatus;
        this.queries = queries;
    }

    @GetMapping("/status")
    @Operation(summary = "Freshness do snapshot do Forecast: UP_TO_DATE, RECALCULATING ou NOT_BUILT")
    @ApiResponse(responseCode = "200", description = "Estado do snapshot")
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ForecastStatusResponse status(@PathVariable UUID workspaceId) {
        return ForecastStatusResponse.from(getStatus.execute(workspaceId));
    }

    @GetMapping("/month")
    @Operation(operationId = "forecastMonth",
            summary = "Despesa, receita e resultado projetados do mês corrente, com os pendentes (ADR-0030 3.1)")
    @ApiResponse(responseCode = "200", description = "Projeção do mês, com estado e freshness")
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ForecastMonthResponse month(@PathVariable UUID workspaceId) {
        return ForecastMonthResponse.of(queries.month(workspaceId));
    }

    @GetMapping("/next-income")
    @Operation(operationId = "forecastNextIncome",
            summary = "A próxima receita prevista: fonte, data, conta e valor (ADR-0030 3.2)")
    @ApiResponse(responseCode = "200", description = "Próxima receita, com estado e freshness")
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ForecastNextIncomeResponse nextIncome(@PathVariable UUID workspaceId) {
        return ForecastNextIncomeResponse.of(queries.nextIncome(workspaceId));
    }

    @GetMapping("/upcoming")
    @Operation(operationId = "forecastUpcoming",
            summary = "Agenda de 30 dias completa e ordenada: eventos de fatura (fatos) e recorrências estimadas "
                    + "(ADR-0030 3.3)")
    @ApiResponse(responseCode = "200", description = "Próximos eventos, com estado e freshness")
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ForecastUpcomingResponse upcoming(@PathVariable UUID workspaceId) {
        return ForecastUpcomingResponse.of(queries.upcoming(workspaceId));
    }

    @GetMapping("/invoices")
    @Operation(operationId = "forecastInvoices",
            summary = "A fatura aberta (parcial + fechamento estimado) e as futuras até o horizonte, por cartão "
                    + "(ADR-0030 3.5)")
    @ApiResponse(responseCode = "200", description = "Faturas projetadas, com estado e freshness")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (cardId INVALID)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND ou CARD_NOT_FOUND (inexistente ou de outro "
            + "Workspace)", content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ForecastInvoicesResponse invoices(@PathVariable UUID workspaceId,
                                             @Parameter(description = "Cartão do Workspace; omitido = todos os cartões")
                                             @RequestParam(required = false) String cardId) {
        return ForecastInvoicesResponse.of(queries.invoices(workspaceId, cardId));
    }

    @GetMapping("/cash-flow")
    @Operation(operationId = "forecastCashFlow",
            summary = "Os meses do horizonte com a faixa e o menor saldo projetado (ADR-0030 3.4)")
    @ApiResponse(responseCode = "200", description = "Fluxo de caixa projetado, com estado e freshness")
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ForecastCashFlowResponse cashFlow(@PathVariable UUID workspaceId) {
        return ForecastCashFlowResponse.of(queries.cashFlow(workspaceId));
    }
}
