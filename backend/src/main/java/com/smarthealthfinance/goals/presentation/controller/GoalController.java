package com.smarthealthfinance.goals.presentation.controller;

import com.smarthealthfinance.goals.application.dto.GoalView;
import com.smarthealthfinance.goals.application.usecase.AddGoalContribution;
import com.smarthealthfinance.goals.application.usecase.ApplyGoalPlan;
import com.smarthealthfinance.goals.application.usecase.ChangeGoalStatus;
import com.smarthealthfinance.goals.application.usecase.CreateGoal;
import com.smarthealthfinance.goals.application.usecase.GetGoal;
import com.smarthealthfinance.goals.application.usecase.ListGoalContributions;
import com.smarthealthfinance.goals.application.usecase.ListGoals;
import com.smarthealthfinance.goals.application.usecase.ListRecentContributions;
import com.smarthealthfinance.goals.application.usecase.UpdateGoal;
import com.smarthealthfinance.goals.domain.enums.GoalStatusAction;
import com.smarthealthfinance.goals.presentation.dto.request.ApplyGoalPlanRequest;
import com.smarthealthfinance.goals.presentation.dto.request.CreateGoalRequest;
import com.smarthealthfinance.goals.presentation.dto.request.GoalContributionRequest;
import com.smarthealthfinance.goals.presentation.dto.request.UpdateGoalRequest;
import com.smarthealthfinance.goals.presentation.dto.response.GoalContributionsResponse;
import com.smarthealthfinance.goals.presentation.dto.response.GoalListResponse;
import com.smarthealthfinance.goals.presentation.dto.response.GoalResponse;
import com.smarthealthfinance.goals.presentation.dto.response.RecentContributionsResponse;
import com.smarthealthfinance.shared.presentation.error.ApiError;
import com.smarthealthfinance.transactions.application.usecase.CreateTransaction;
import com.smarthealthfinance.transactions.presentation.dto.response.TransactionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/**
 * Objetivos e aportes (ADR-0034, ADR-0035). O progresso e os aportes são fatos lidos do destino; o aporte é uma
 * transferência criada pelo caso de uso de Transactions.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/goals")
@Tag(name = "Goals")
public class GoalController {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final ListGoals listGoals;
    private final GetGoal getGoal;
    private final CreateGoal createGoal;
    private final UpdateGoal updateGoal;
    private final ApplyGoalPlan applyGoalPlan;
    private final ChangeGoalStatus changeStatus;
    private final AddGoalContribution addContribution;
    private final ListGoalContributions listContributions;
    private final ListRecentContributions recentContributions;

    public GoalController(ListGoals listGoals, GetGoal getGoal, CreateGoal createGoal, UpdateGoal updateGoal,
                          ApplyGoalPlan applyGoalPlan, ChangeGoalStatus changeStatus,
                          AddGoalContribution addContribution, ListGoalContributions listContributions,
                          ListRecentContributions recentContributions) {
        this.listGoals = listGoals;
        this.getGoal = getGoal;
        this.createGoal = createGoal;
        this.updateGoal = updateGoal;
        this.applyGoalPlan = applyGoalPlan;
        this.changeStatus = changeStatus;
        this.addContribution = addContribution;
        this.listContributions = listContributions;
        this.recentContributions = recentContributions;
    }

    @GetMapping
    @Operation(operationId = "listGoals",
            summary = "Lista os objetivos (sem filtro: ativos e pausados) com o resumo dos abertos")
    @ApiResponse(responseCode = "200", description = "Objetivos, mais recentes primeiro, com o progresso (fato)")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (status INVALID)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public GoalListResponse list(@PathVariable UUID workspaceId,
                                 @Parameter(description = "ACTIVE, PAUSED, COMPLETED ou ARCHIVED")
                                 @RequestParam(required = false) String status) {
        return GoalListResponse.from(listGoals.execute(workspaceId, status));
    }

    @PostMapping
    @Operation(operationId = "createGoal", summary = "Cria um objetivo")
    @ApiResponse(responseCode = "201", description = "Objetivo criado")
    @ApiResponse(responseCode = "400",
            description = "VALIDATION_FAILED (destinationAccountId REQUIRED/NOT_ALLOWED/SAVINGS_NOT_ALLOWED, "
                    + "deadline PAST, sourceAccountId SAME_AS_DESTINATION, ...)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND ou ACCOUNT_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409",
            description = "ACCOUNT_ARCHIVED, GOAL_DESTINATION_IN_USE ou GOAL_RESERVE_ALREADY_EXISTS",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<GoalResponse> create(@PathVariable UUID workspaceId,
                                               @Valid @RequestBody CreateGoalRequest request) {
        GoalView view = createGoal.execute(workspaceId, new CreateGoal.Command(request.name(), request.type(),
                request.targetAmount().amount(), request.targetAmount().currency(),
                request.plannedMonthly().amount(), request.plannedMonthly().currency(), request.deadline(),
                request.sourceAccountId(), request.destinationAccountId(),
                request.origin() == null ? null : request.origin().kind(),
                request.origin() == null ? null : request.origin().insightId()));
        return ResponseEntity.created(URI.create("/api/v1/workspaces/" + workspaceId + "/goals/" + view.id()))
                .body(GoalResponse.from(view));
    }

    @GetMapping("/recent-contributions")
    @Operation(operationId = "listRecentGoalContributions",
            summary = "Aportes recentes (fato) dos objetivos ativos e pausados")
    @ApiResponse(responseCode = "200", description = "Aportes, mais recentes primeiro")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (limit OUT_OF_RANGE)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public RecentContributionsResponse recentContributions(
            @PathVariable UUID workspaceId,
            @Parameter(description = "1 a 20 (padrão 5)") @RequestParam(required = false) Integer limit) {
        return RecentContributionsResponse.from(recentContributions.execute(workspaceId, limit));
    }

    @GetMapping("/{goalId}")
    @Operation(operationId = "getGoal", summary = "Detalhe do objetivo com o progresso (fato)")
    @ApiResponse(responseCode = "200", description = "Objetivo")
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND ou GOAL_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public GoalResponse get(@PathVariable UUID workspaceId, @PathVariable UUID goalId) {
        return GoalResponse.from(getGoal.execute(workspaceId, goalId));
    }

    @PutMapping("/{goalId}")
    @Operation(operationId = "updateGoal",
            summary = "Edita nome, alvo, aporte planejado, prazo, conta de origem e destino do objetivo")
    @ApiResponse(responseCode = "200", description = "Objetivo atualizado")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND, GOAL_NOT_FOUND ou ACCOUNT_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409",
            description = "GOAL_STATUS_CONFLICT, ACCOUNT_ARCHIVED, GOAL_DESTINATION_IN_USE ou CONFLICT",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public GoalResponse update(@PathVariable UUID workspaceId, @PathVariable UUID goalId,
                               @Valid @RequestBody UpdateGoalRequest request) {
        return GoalResponse.from(updateGoal.execute(workspaceId, goalId, new UpdateGoal.Command(request.name(),
                request.targetAmount().amount(), request.targetAmount().currency(),
                request.plannedMonthly().amount(), request.plannedMonthly().currency(), request.deadline(),
                request.sourceAccountId(), request.destinationAccountId())));
    }

    @PutMapping("/{goalId}/plan")
    @Operation(operationId = "applyGoalPlan",
            summary = "Aplica um novo aporte planejado e prazo ao objetivo (\"Aplicar ao objetivo\")")
    @ApiResponse(responseCode = "200", description = "Plano aplicado; nenhum dado financeiro muda")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (deadline PAST, ...)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND ou GOAL_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "GOAL_STATUS_CONFLICT ou CONFLICT",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public GoalResponse applyPlan(@PathVariable UUID workspaceId, @PathVariable UUID goalId,
                                  @Valid @RequestBody ApplyGoalPlanRequest request) {
        return GoalResponse.from(applyGoalPlan.execute(workspaceId, goalId, new ApplyGoalPlan.Command(
                request.plannedMonthly().amount(), request.plannedMonthly().currency(), request.deadline())));
    }

    @PostMapping("/{goalId}/pause")
    @Operation(operationId = "pauseGoal", summary = "Pausa o objetivo (idempotente)")
    @ApiResponse(responseCode = "200", description = "Objetivo pausado")
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND ou GOAL_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "GOAL_STATUS_CONFLICT",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public GoalResponse pause(@PathVariable UUID workspaceId, @PathVariable UUID goalId) {
        return GoalResponse.from(changeStatus.execute(workspaceId, goalId, GoalStatusAction.PAUSE));
    }

    @PostMapping("/{goalId}/resume")
    @Operation(operationId = "resumeGoal", summary = "Retoma o objetivo pausado (idempotente)")
    @ApiResponse(responseCode = "200", description = "Objetivo ativo")
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND ou GOAL_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "GOAL_STATUS_CONFLICT",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public GoalResponse resume(@PathVariable UUID workspaceId, @PathVariable UUID goalId) {
        return GoalResponse.from(changeStatus.execute(workspaceId, goalId, GoalStatusAction.RESUME));
    }

    @PostMapping("/{goalId}/complete")
    @Operation(operationId = "completeGoal",
            summary = "Conclui o objetivo e congela o progresso atual (idempotente)")
    @ApiResponse(responseCode = "200", description = "Objetivo concluído")
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND ou GOAL_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "GOAL_STATUS_CONFLICT",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public GoalResponse complete(@PathVariable UUID workspaceId, @PathVariable UUID goalId) {
        return GoalResponse.from(changeStatus.execute(workspaceId, goalId, GoalStatusAction.COMPLETE));
    }

    @PostMapping("/{goalId}/archive")
    @Operation(operationId = "archiveGoal", summary = "Arquiva o objetivo (idempotente; terminal)")
    @ApiResponse(responseCode = "200", description = "Objetivo arquivado")
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND ou GOAL_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public GoalResponse archive(@PathVariable UUID workspaceId, @PathVariable UUID goalId) {
        return GoalResponse.from(changeStatus.execute(workspaceId, goalId, GoalStatusAction.ARCHIVE));
    }

    @PostMapping("/{goalId}/contributions")
    @Operation(operationId = "addGoalContribution",
            summary = "Registra um aporte: transferência da origem para o destino do objetivo (idempotente)")
    @ApiResponse(responseCode = "201", description = "Aporte registrado (a transferência criada)")
    @ApiResponse(responseCode = "200", description = "Replay: a mesma chave e conteúdo já foram processados")
    @ApiResponse(responseCode = "400",
            description = "VALIDATION_FAILED (Idempotency-Key ausente, occurredOn FUTURE/BEFORE_TRACKING_START, "
                    + "destinationAccountId REQUIRED/NOT_ALLOWED/NOT_RESERVE_ACCOUNT, ...)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND, GOAL_NOT_FOUND ou ACCOUNT_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "GOAL_STATUS_CONFLICT ou ACCOUNT_ARCHIVED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "422", description = "IDEMPOTENCY_KEY_REUSED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<TransactionResponse> addContribution(
            @PathVariable UUID workspaceId, @PathVariable UUID goalId,
            @Parameter(description = "Chave única por intenção", required = true)
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody GoalContributionRequest request) {
        CreateTransaction.Result result = addContribution.execute(workspaceId, goalId, new AddGoalContribution.Command(
                idempotencyKey, request.sourceAccountId(), request.destinationAccountId(), request.amount().amount(),
                request.amount().currency(), request.occurredOn(), request.description()));
        TransactionResponse body = TransactionResponse.from(result.transaction());
        return result.replayed() ? ResponseEntity.ok(body)
                : ResponseEntity.created(URI.create("/api/v1/workspaces/" + workspaceId + "/transactions/"
                        + result.transaction().id())).body(body);
    }

    @GetMapping("/{goalId}/contributions")
    @Operation(operationId = "listGoalContributions",
            summary = "Histórico do destino: saldo inicial e movimentos (aportes, resgates e outros), fatos")
    @ApiResponse(responseCode = "200", description = "Movimentos, mais recentes primeiro")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (page ou pageSize OUT_OF_RANGE)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND ou GOAL_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public GoalContributionsResponse contributions(
            @PathVariable UUID workspaceId, @PathVariable UUID goalId,
            @Parameter(description = "0-based (padrão 0)") @RequestParam(required = false) Integer page,
            @Parameter(description = "1 a 50 (padrão 20)") @RequestParam(required = false) Integer pageSize) {
        return GoalContributionsResponse.from(listContributions.execute(workspaceId, goalId, page, pageSize));
    }
}
