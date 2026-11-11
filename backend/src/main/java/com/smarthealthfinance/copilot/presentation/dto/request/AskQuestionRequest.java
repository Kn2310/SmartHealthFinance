package com.smarthealthfinance.copilot.presentation.dto.request;

import com.smarthealthfinance.copilot.application.dto.AskCommand;
import com.smarthealthfinance.copilot.domain.model.EntryContext;
import com.smarthealthfinance.shared.domain.InvalidValueException;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

/**
 * Pergunta ao Copilot (ADR-0040 §3). A pergunta e as anteriores são validadas depois da admissão (limites no caso de
 * uso), então este DTO não valida o tamanho. Ids e mês chegam como texto para um valor mal formado virar
 * {@code VALIDATION_FAILED} (e não {@code MALFORMED_REQUEST}).
 */
@Schema(name = "CopilotAskQuestionRequest")
public record AskQuestionRequest(
        @Schema(description = "A pergunta, de 1 a 500 caracteres depois do strip", example = "Por que minha fatura "
                + "está mais alta?")
        String question,
        @Schema(description = "Conversa guardada a continuar (só com COPILOT_HISTORY ativo); não combina com "
                + "previousQuestions", format = "uuid", nullable = true)
        String conversationId,
        @Schema(description = "Até 3 perguntas anteriores da própria pessoa, cada uma até 500 caracteres; só na "
                + "conversa efêmera", nullable = true)
        List<String> previousQuestions,
        @Schema(description = "Ponto de entrada do 'Perguntar ao Copilot'; o backend valida que o alvo existe no "
                + "Workspace", nullable = true)
        Context context) {

    /**
     * Contexto de entrada: {@code kind} {@code INSIGHT} (insightId), {@code INVOICE} (cardId, month),
     * {@code INSTALLMENT_PLAN} (cardId, planId) ou {@code GOAL} (goalId).
     */
    @Schema(name = "CopilotEntryContext")
    public record Context(
            @Schema(allowableValues = {"INSIGHT", "INVOICE", "INSTALLMENT_PLAN", "GOAL"}) String kind,
            @Schema(format = "uuid", nullable = true) String insightId,
            @Schema(format = "uuid", nullable = true) String cardId,
            @Schema(description = "Mês de vencimento da fatura (YYYY-MM)", example = "2026-10", nullable = true)
            String month,
            @Schema(format = "uuid", nullable = true) String planId,
            @Schema(format = "uuid", nullable = true) String goalId) {
    }

    /** O comando do caso de uso; {@code context} mal formado é {@code VALIDATION_FAILED}. */
    public AskCommand toCommand() {
        return new AskCommand(question, previousQuestions, context == null ? null : entryContext(context));
    }

    /** {@code conversationId} como UUID, ou nulo. */
    public UUID conversation() {
        return conversationId == null ? null : uuid("conversationId", conversationId);
    }

    private static EntryContext entryContext(Context context) {
        if (context.kind() == null) {
            throw new InvalidValueException("context.kind", "REQUIRED");
        }
        return switch (context.kind()) {
            case "INSIGHT" -> new EntryContext.Insight(uuid("context.insightId", context.insightId()));
            case "INVOICE" -> new EntryContext.Invoice(uuid("context.cardId", context.cardId()),
                    month(context.month()));
            case "INSTALLMENT_PLAN" -> new EntryContext.InstallmentPlan(uuid("context.cardId", context.cardId()),
                    uuid("context.planId", context.planId()));
            case "GOAL" -> new EntryContext.Goal(uuid("context.goalId", context.goalId()));
            default -> throw new InvalidValueException("context.kind", "INVALID");
        };
    }

    private static UUID uuid(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new InvalidValueException(field, "REQUIRED");
        }
        try {
            return UUID.fromString(value.strip());
        } catch (IllegalArgumentException e) {
            throw new InvalidValueException(field, "INVALID");
        }
    }

    private static YearMonth month(String value) {
        if (value == null || value.isBlank()) {
            throw new InvalidValueException("context.month", "REQUIRED");
        }
        try {
            return YearMonth.parse(value.strip());
        } catch (DateTimeParseException e) {
            throw new InvalidValueException("context.month", "INVALID");
        }
    }

    /** Sem o texto das perguntas. */
    @Override
    public String toString() {
        return "AskQuestionRequest[" + (previousQuestions == null ? 0 : previousQuestions.size()) + " previous]";
    }
}
