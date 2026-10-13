package com.smarthealthfinance.analytics.presentation.controller;

import com.smarthealthfinance.analytics.application.service.AnalysisContextLoader.Query;
import com.smarthealthfinance.analytics.application.usecase.AnalysisQueries;
import com.smarthealthfinance.analytics.application.usecase.GetAnalyticsStatus;
import com.smarthealthfinance.analytics.presentation.dto.response.AnalysisResponses.AnalysisCategoriesResponse;
import com.smarthealthfinance.analytics.presentation.dto.response.AnalysisResponses.AnalysisCategoryDetailResponse;
import com.smarthealthfinance.analytics.presentation.dto.response.AnalysisResponses.AnalysisEvolutionResponse;
import com.smarthealthfinance.analytics.presentation.dto.response.AnalysisResponses.AnalysisIncomeResponse;
import com.smarthealthfinance.analytics.presentation.dto.response.AnalysisResponses.AnalysisMerchantsResponse;
import com.smarthealthfinance.analytics.presentation.dto.response.AnalysisResponses.AnalysisOverviewResponse;
import com.smarthealthfinance.analytics.presentation.dto.response.AnalysisResponses.AnalysisSpendingResponse;
import com.smarthealthfinance.analytics.presentation.dto.response.AnalyticsStatusResponse;
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
 * Análise (ADR-0014/0015): uma rota por aba. Todas aceitam {@code month} (YYYY-MM, padrão o mês corrente, parcial
 * até hoje), {@code compareWith} (PREVIOUS ou AVERAGE) e {@code averageMonths} (1–12, padrão 5).
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/analytics")
@Tag(name = "Analytics")
public class AnalyticsController {

    private static final String MONTH = "Mês analisado (YYYY-MM); padrão o mês corrente, parcial até hoje";
    private static final String COMPARE = "PREVIOUS (padrão: mesmos dias do mês anterior) ou AVERAGE (média)";
    private static final String AVERAGE = "Meses da média, 1 a 12 (padrão 5), no mesmo recorte de dias";
    private static final String ERRORS = "VALIDATION_FAILED (month INVALID/FUTURE, compareWith INVALID, "
            + "averageMonths OUT_OF_RANGE e os do próprio endpoint)";

    private final GetAnalyticsStatus getStatus;
    private final AnalysisQueries queries;

    public AnalyticsController(GetAnalyticsStatus getStatus, AnalysisQueries queries) {
        this.getStatus = getStatus;
        this.queries = queries;
    }

    @GetMapping("/status")
    @Operation(summary = "Freshness da projeção de Analytics: UP_TO_DATE, UPDATING (eventos pendentes) ou NOT_BUILT")
    @ApiResponse(responseCode = "200", description = "Estado da projeção")
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public AnalyticsStatusResponse status(@PathVariable UUID workspaceId) {
        return AnalyticsStatusResponse.from(getStatus.execute(workspaceId));
    }

    @GetMapping("/overview")
    @Operation(summary = "Visão geral: receitas, despesas, resultado, 6 meses, composição e variações do resultado")
    @ApiResponse(responseCode = "200", description = "Análise do mês (state, period, comparison e freshness em toda resposta)")
    @ApiResponse(responseCode = "400", description = ERRORS,
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public AnalysisOverviewResponse overview(@PathVariable UUID workspaceId,
                                             @Parameter(description = MONTH) @RequestParam(required = false) String month,
                                             @Parameter(description = COMPARE) @RequestParam(required = false) String compareWith,
                                             @Parameter(description = AVERAGE) @RequestParam(required = false) Integer averageMonths) {
        return AnalysisOverviewResponse.from(workspaceId,
                queries.overview(workspaceId, new Query(month, compareWith, averageMonths)));
    }

    @GetMapping("/spending")
    @Operation(summary = "Gastos: total, média por dia, acumulado do mês × anterior, maiores gastos e categorias")
    @ApiResponse(responseCode = "200", description = "Análise do mês (state, period, comparison e freshness em toda resposta)")
    @ApiResponse(responseCode = "400", description = ERRORS,
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public AnalysisSpendingResponse spending(@PathVariable UUID workspaceId,
                                             @Parameter(description = MONTH) @RequestParam(required = false) String month,
                                             @Parameter(description = COMPARE) @RequestParam(required = false) String compareWith,
                                             @Parameter(description = AVERAGE) @RequestParam(required = false) Integer averageMonths) {
        return AnalysisSpendingResponse.from(workspaceId,
                queries.spending(workspaceId, new Query(month, compareWith, averageMonths)));
    }

    @GetMapping("/income")
    @Operation(summary = "Receitas: total, média, fontes (recorrente ou pontual) e 6 meses")
    @ApiResponse(responseCode = "200", description = "Análise do mês (state, period, comparison e freshness em toda resposta)")
    @ApiResponse(responseCode = "400", description = ERRORS,
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public AnalysisIncomeResponse income(@PathVariable UUID workspaceId,
                                         @Parameter(description = MONTH) @RequestParam(required = false) String month,
                                         @Parameter(description = COMPARE) @RequestParam(required = false) String compareWith,
                                         @Parameter(description = AVERAGE) @RequestParam(required = false) Integer averageMonths) {
        return AnalysisIncomeResponse.from(workspaceId,
                queries.income(workspaceId, new Query(month, compareWith, averageMonths)));
    }

    @GetMapping("/categories")
    @Operation(summary = "Categorias de despesa do mês com participação, reembolsos e variações")
    @ApiResponse(responseCode = "200", description = "Análise do mês (state, period, comparison e freshness em toda resposta)")
    @ApiResponse(responseCode = "400", description = ERRORS,
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public AnalysisCategoriesResponse categories(@PathVariable UUID workspaceId,
                                                 @Parameter(description = MONTH) @RequestParam(required = false) String month,
                                                 @Parameter(description = COMPARE) @RequestParam(required = false) String compareWith,
                                                 @Parameter(description = AVERAGE) @RequestParam(required = false) Integer averageMonths) {
        return AnalysisCategoriesResponse.from(workspaceId,
                queries.categories(workspaceId, new Query(month, compareWith, averageMonths)));
    }

    @GetMapping("/categories/{categoryCode}")
    @Operation(summary = "Detalhe de uma categoria: valor, compras, ticket médio, 6 meses e maiores comerciantes")
    @ApiResponse(responseCode = "200", description = "Análise do mês (state, period, comparison e freshness em toda resposta)")
    @ApiResponse(responseCode = "400", description = ERRORS + "; categoryCode INVALID (fora do catálogo)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public AnalysisCategoryDetailResponse category(@PathVariable UUID workspaceId, @PathVariable String categoryCode,
                                                   @Parameter(description = MONTH) @RequestParam(required = false) String month,
                                                   @Parameter(description = COMPARE) @RequestParam(required = false) String compareWith,
                                                   @Parameter(description = AVERAGE) @RequestParam(required = false) Integer averageMonths) {
        return AnalysisCategoryDetailResponse.from(workspaceId,
                queries.category(workspaceId, categoryCode, new Query(month, compareWith, averageMonths)));
    }

    @GetMapping("/merchants")
    @Operation(summary = "Comerciantes do mês: ranking por valor ou frequência, categoria principal e variações")
    @ApiResponse(responseCode = "200", description = "Análise do mês (state, period, comparison e freshness em toda resposta)")
    @ApiResponse(responseCode = "400", description = ERRORS + "; sort INVALID",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public AnalysisMerchantsResponse merchants(@PathVariable UUID workspaceId,
                                               @Parameter(description = MONTH) @RequestParam(required = false) String month,
                                               @Parameter(description = COMPARE) @RequestParam(required = false) String compareWith,
                                               @Parameter(description = AVERAGE) @RequestParam(required = false) Integer averageMonths,
                                               @Parameter(description = "AMOUNT (padrão) ou COUNT")
                                               @RequestParam(required = false) String sort) {
        return AnalysisMerchantsResponse.from(workspaceId,
                queries.merchants(workspaceId, new Query(month, compareWith, averageMonths), sort));
    }

    @GetMapping("/evolution")
    @Operation(summary = "Evolução mês a mês (receitas, despesas, reembolsos, resultado) até o mês analisado")
    @ApiResponse(responseCode = "200", description = "Análise do mês (state, period, comparison e freshness em toda resposta)")
    @ApiResponse(responseCode = "400", description = ERRORS + "; months OUT_OF_RANGE",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public AnalysisEvolutionResponse evolution(@PathVariable UUID workspaceId,
                                               @Parameter(description = MONTH) @RequestParam(required = false) String month,
                                               @Parameter(description = "Quantos meses, 1 a 12 (padrão 6)")
                                               @RequestParam(required = false) Integer months) {
        return AnalysisEvolutionResponse.from(workspaceId,
                queries.evolution(workspaceId, new Query(month, null, null), months));
    }
}
