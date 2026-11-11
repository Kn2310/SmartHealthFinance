package com.smarthealthfinance.copilot.presentation.controller;

import com.smarthealthfinance.copilot.application.service.SubmitCopilotQuestion;
import com.smarthealthfinance.copilot.application.usecase.GetCopilotUsage;
import com.smarthealthfinance.copilot.application.usecase.ManageConversations;
import com.smarthealthfinance.copilot.application.usecase.SubmitFeedback;
import com.smarthealthfinance.copilot.presentation.dto.request.AskQuestionRequest;
import com.smarthealthfinance.copilot.presentation.dto.request.FeedbackRequest;
import com.smarthealthfinance.copilot.presentation.dto.response.CopilotResponses.ConversationPageResponse;
import com.smarthealthfinance.copilot.presentation.dto.response.CopilotResponses.ConversationResponse;
import com.smarthealthfinance.copilot.presentation.dto.response.CopilotResponses.CopilotAnswerResponse;
import com.smarthealthfinance.copilot.presentation.dto.response.CopilotResponses.UsageResponse;
import com.smarthealthfinance.copilot.presentation.handler.CopilotExceptionHandler.QuotaExceededError;
import com.smarthealthfinance.shared.presentation.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * API do Financial Copilot (ADR-0040): perguntas, histórico, uso e feedback. Resposta completa e síncrona, sem streaming: a Financial Claim
 * Validation precisa da resposta inteira antes de mostrar qualquer texto (ADR-0039 §7). Nada de pergunta, resposta ou
 * valor vai para log ou métrica.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/copilot")
@Tag(name = "Copilot")
public class CopilotController {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final SubmitCopilotQuestion submitQuestion;
    private final GetCopilotUsage getUsage;
    private final ManageConversations conversations;
    private final SubmitFeedback submitFeedback;

    public CopilotController(SubmitCopilotQuestion submitQuestion, GetCopilotUsage getUsage,
                             ManageConversations conversations, SubmitFeedback submitFeedback) {
        this.submitQuestion = submitQuestion;
        this.getUsage = getUsage;
        this.conversations = conversations;
        this.submitFeedback = submitFeedback;
    }

    @PostMapping("/questions")
    @Operation(summary = "Pergunta ao Copilot (resposta completa, idempotente por Idempotency-Key)")
    @ApiResponse(responseCode = "200", description = "Resposta com code OK, SAFE_MODE, INSUFFICIENT_DATA ou "
            + "OUT_OF_SCOPE")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (pergunta vazia ou longa, previousQuestions "
            + "acima de 3 ou longas, conversationId com previousQuestions, contexto mal formado, Idempotency-Key "
            + "ausente ou inválida) ou MALFORMED_REQUEST",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "403", description = "COPILOT_CONSENT_REQUIRED (sem COPILOT_DATA_USE ativo)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND, COPILOT_CONTEXT_NOT_FOUND (alvo fora do "
            + "Workspace) ou COPILOT_CONVERSATION_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "COPILOT_REQUEST_IN_PROGRESS (replay em andamento) ou "
            + "COPILOT_ALREADY_ANSWERED (replay de pergunta já respondida numa conversa sem histórico)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "422", description = "IDEMPOTENCY_KEY_REUSED (mesma chave com outro conteúdo)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "429", description = "COPILOT_RATE_LIMITED (header Retry-After, em segundos) ou "
            + "COPILOT_QUOTA_EXCEEDED (resetsAt no corpo)",
            headers = @Header(name = "Retry-After", description = "Segundos até a próxima pergunta (só no rate limit)",
                    schema = @Schema(type = "integer")),
            content = @Content(schema = @Schema(implementation = QuotaExceededError.class)))
    @ApiResponse(responseCode = "503", description = "COPILOT_UNAVAILABLE (provedor fora, circuito aberto, Copilot "
            + "desligado ou Redis fora)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public CopilotAnswerResponse ask(
            @PathVariable UUID workspaceId,
            @Parameter(description = "Chave única por pergunta; vale 10 minutos", required = true)
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestBody AskQuestionRequest request) {
        return CopilotAnswerResponse.from(submitQuestion.submit(workspaceId, idempotencyKey, request.conversation(),
                request.toCommand()));
    }

    @GetMapping("/conversations")
    @Operation(summary = "Conversas guardadas da pessoa no Workspace, da mais recente para a mais antiga. Sem "
            + "COPILOT_HISTORY: historyEnabled false e lista vazia")
    @ApiResponse(responseCode = "200", description = "Página de conversas, com nextCursor quando há mais")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (cursor before inválido)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ConversationPageResponse conversations(
            @PathVariable UUID workspaceId,
            @Parameter(description = "Itens por página; padrão 20, máximo 50") @RequestParam(required = false)
            Integer limit,
            @Parameter(description = "Cursor opaco (nextCursor) da página anterior") @RequestParam(required = false)
            String before) {
        return ConversationPageResponse.from(conversations.list(workspaceId, limit, before));
    }

    @GetMapping("/conversations/{conversationId}")
    @Operation(summary = "Conversa guardada com as mensagens não expiradas (foto de cada momento)")
    @ApiResponse(responseCode = "200", description = "Conversa com as mensagens, da mais antiga para a mais nova")
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND ou COPILOT_CONVERSATION_NOT_FOUND "
            + "(inexistente, de outra pessoa, expirada ou sem COPILOT_HISTORY)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ConversationResponse conversation(@PathVariable UUID workspaceId, @PathVariable UUID conversationId) {
        return ConversationResponse.from(conversations.get(workspaceId, conversationId));
    }

    @DeleteMapping("/conversations/{conversationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Apaga uma conversa guardada (hard delete)")
    @ApiResponse(responseCode = "204", description = "Conversa apagada")
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND ou COPILOT_CONVERSATION_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public void deleteConversation(@PathVariable UUID workspaceId, @PathVariable UUID conversationId) {
        conversations.delete(workspaceId, conversationId);
    }

    @DeleteMapping("/conversations")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Apaga todas as conversas da pessoa no Workspace (hard delete, idempotente)")
    @ApiResponse(responseCode = "204", description = "Conversas apagadas (ou não havia nenhuma)")
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public void deleteAllConversations(@PathVariable UUID workspaceId) {
        conversations.deleteAll(workspaceId);
    }

    @PutMapping("/questions/{requestId}/feedback")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Avalia uma resposta (último envio vale). Exige só membership, não consentimento; sem "
            + "texto livre")
    @ApiResponse(responseCode = "204", description = "Feedback registrado")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (avaliação ausente ou inválida, motivo "
            + "inválido)", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND ou COPILOT_REQUEST_NOT_FOUND (pergunta que "
            + "não é da pessoa no Workspace)", content = @Content(schema = @Schema(implementation = ApiError.class)))
    public void feedback(@PathVariable UUID workspaceId, @PathVariable UUID requestId,
                         @RequestBody FeedbackRequest request) {
        submitFeedback.execute(workspaceId, requestId, request.ratingValue(), request.reasonValue());
    }

    @GetMapping("/usage")
    @Operation(summary = "Uso do Copilot no Workspace: perguntas do mês e do dia (limites provisórios) e o recorte "
            + "de dados. Exige só membership, não consentimento")
    @ApiResponse(responseCode = "200", description = "Uso calculado no usage ledger, com a mesma regra da quota")
    @ApiResponse(responseCode = "404", description = "WORKSPACE_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public UsageResponse usage(@PathVariable UUID workspaceId) {
        return UsageResponse.from(getUsage.execute(workspaceId));
    }
}
