package com.smarthealthfinance.insights.presentation.controller;

import com.smarthealthfinance.insights.application.usecase.GetInsightsStatus;
import com.smarthealthfinance.insights.application.usecase.InsightActions;
import com.smarthealthfinance.insights.application.usecase.InsightQueries;
import com.smarthealthfinance.insights.presentation.dto.request.InsightFeedbackRequest;
import com.smarthealthfinance.insights.presentation.dto.response.InsightResponses.InsightDetailResponse;
import com.smarthealthfinance.insights.presentation.dto.response.InsightResponses.InsightHighlightsResponse;
import com.smarthealthfinance.insights.presentation.dto.response.InsightResponses.InsightItemDto;
import com.smarthealthfinance.insights.presentation.dto.response.InsightResponses.InsightListResponse;
import com.smarthealthfinance.insights.presentation.dto.response.InsightResponses.InsightRelatedResponse;
import com.smarthealthfinance.insights.presentation.dto.response.InsightsStatusResponse;
import com.smarthealthfinance.shared.presentation.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Insights (ADR-0021/0022/0023): leituras do que a geração gravou e ações do usuário. Nada é gerado na requisição;
 * as ações são idempotentes pelo estado e não disparam nova geração.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/insights")
@Tag(name = "Insights")
public class InsightsController {

    private static final String NOT_FOUND = "WORKSPACE_NOT_FOUND ou INSIGHT_NOT_FOUND";

    private final GetInsightsStatus getStatus;
    private final InsightQueries queries;
    private final InsightActions actions;

    public InsightsController(GetInsightsStatus getStatus, InsightQueries queries, InsightActions actions) {
        this.getStatus = getStatus;
        this.queries = queries;
        this.actions = actions;
    }

    @GetMapping
    @Operation(operationId = "listInsights",
            summary = "Insights na ordem do ADR-0021 §3, com as contagens dos chips (sempre sobre os ativos)")
    @ApiResponse(responseCode = "200", description = "Página de insights, com estado e freshness")
    @ApiResponse(responseCode = "400",
            description = "VALIDATION_FAILED (importance, type ou state INVALID; page ou pageSize OUT_OF_RANGE)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public InsightListResponse list(@PathVariable UUID workspaceId,
                                    @Parameter(description = "IMPORTANT ou INFORMATIVE; omitido = todas")
                                    @RequestParam(required = false) String importance,
                                    @Parameter(description = "SPENDING, INCOME, HEALTH ou CARDS")
                                    @RequestParam(required = false) String type,
                                    @Parameter(description = "ACTIVE (padrão) ou DISMISSED")
                                    @RequestParam(required = false) String state,
                                    @Parameter(description = "Página, a partir de 0")
                                    @RequestParam(required = false) Integer page,
                                    @Parameter(description = "Itens por página, 1 a 50 (padrão 20)")
                                    @RequestParam(required = false) Integer pageSize) {
        return InsightListResponse.from(workspaceId, queries.list(workspaceId, importance, type, state, page,
                pageSize));
    }

    @GetMapping("/highlights")
    @Operation(operationId = "insightHighlights",
            summary = "Home: \"Merece atenção\" (até 2 Importantes), \"O que mudou?\" (até 3 linhas) e Importantes ativos")
    @ApiResponse(responseCode = "200", description = "Destaques, com estado e freshness")
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public InsightHighlightsResponse highlights(@PathVariable UUID workspaceId) {
        return InsightHighlightsResponse.from(workspaceId, queries.highlights(workspaceId));
    }

    @GetMapping("/related")
    @Operation(operationId = "relatedInsights",
            summary = "Até 3 insights ativos ligados a uma transação ou a uma dimensão do Health")
    @ApiResponse(responseCode = "200", description = "Insights relacionados")
    @ApiResponse(responseCode = "400",
            description = "VALIDATION_FAILED (transactionId ou dimension INVALID; informe exatamente um dos dois)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND ou TRANSACTION_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public InsightRelatedResponse related(@PathVariable UUID workspaceId,
                                          @Parameter(description = "Transação do Workspace")
                                          @RequestParam(required = false) String transactionId,
                                          @Parameter(description = "CASH_FLOW, SPENDING, CREDIT, DEBT, RESERVE ou "
                                                  + "CONSISTENCY")
                                          @RequestParam(required = false) String dimension) {
        return InsightRelatedResponse.from(workspaceId, queries.related(workspaceId, transactionId, dimension));
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

    @GetMapping("/{insightId}")
    @Operation(operationId = "insightDetail",
            summary = "Detalhe em qualquer estado: resumo, evidências, explicação, limitações, fontes, ações e relacionados")
    @ApiResponse(responseCode = "200", description = "Detalhe do insight")
    @ApiResponse(responseCode = "404", description = NOT_FOUND,
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public InsightDetailResponse detail(@PathVariable UUID workspaceId, @PathVariable UUID insightId) {
        return InsightDetailResponse.from(workspaceId, queries.detail(workspaceId, insightId));
    }

    @PostMapping("/{insightId}/seen")
    @Operation(operationId = "markInsightSeen", summary = "Marca como visto (idempotente)")
    @ApiResponse(responseCode = "200", description = "Insight atualizado")
    @ApiResponse(responseCode = "404", description = NOT_FOUND,
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public InsightItemDto seen(@PathVariable UUID workspaceId, @PathVariable UUID insightId) {
        return InsightItemDto.of(actions.markSeen(workspaceId, insightId));
    }

    @PostMapping("/{insightId}/dismiss")
    @Operation(operationId = "dismissInsight",
            summary = "Dispensa um insight ativo (idempotente); dispensado nunca volta, em nenhuma versão")
    @ApiResponse(responseCode = "200", description = "Insight dispensado")
    @ApiResponse(responseCode = "404", description = NOT_FOUND,
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "INSIGHT_NOT_ACTIVE (resolvido ou expirado)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public InsightItemDto dismiss(@PathVariable UUID workspaceId, @PathVariable UUID insightId) {
        return InsightItemDto.of(actions.dismiss(workspaceId, insightId));
    }

    @PutMapping("/{insightId}/feedback")
    @Operation(operationId = "insightFeedback",
            summary = "Define (USEFUL, NOT_USEFUL) ou remove (null) o feedback; vale o último")
    @ApiResponse(responseCode = "200", description = "Insight atualizado")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (value INVALID)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = NOT_FOUND,
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public InsightItemDto feedback(@PathVariable UUID workspaceId, @PathVariable UUID insightId,
                                   @RequestBody InsightFeedbackRequest request) {
        return InsightItemDto.of(actions.feedback(workspaceId, insightId, request.value()));
    }
}
