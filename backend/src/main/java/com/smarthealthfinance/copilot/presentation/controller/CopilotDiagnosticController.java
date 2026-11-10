package com.smarthealthfinance.copilot.presentation.controller;

import com.smarthealthfinance.copilot.application.dto.CopilotResult;
import com.smarthealthfinance.copilot.application.usecase.AskCopilot;
import com.smarthealthfinance.copilot.domain.model.RenderedAnswer;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Diagnóstico do pipeline do Copilot (Sessão 8.1, Parte C, aprovado pelo usuário em 2026-11-10): existe só no perfil
 * {@code local}, fica fora do OpenAPI e serve para o smoke test com a chave real (runbook). Passa pela mesma
 * autenticação, membership, consentimento, rate limit e quota do caso de uso. A API de conversa é da Sessão 8.2, que
 * substitui este endpoint.
 */
@Hidden
@Profile("local")
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/copilot/diagnostic")
public class CopilotDiagnosticController {

    private final AskCopilot askCopilot;

    public CopilotDiagnosticController(AskCopilot askCopilot) {
        this.askCopilot = askCopilot;
    }

    /** Pergunta de diagnóstico. */
    public record DiagnosticRequest(String question) {}

    /** Resultado do pipeline como a 8.2 o expõe; sem dado além do que a pessoa veria. */
    public record DiagnosticResponse(UUID requestId, String code, String intent, String type, String text,
                                     List<String> explanation, List<RenderedAnswer.Value> financialData,
                                     List<RenderedAnswer.Action> actions, String confidence, String validation,
                                     Long retryAfterSeconds, Instant resetsAt, Map<String, String> versions) {

        static DiagnosticResponse from(CopilotResult result) {
            RenderedAnswer answer = result.answer();
            return new DiagnosticResponse(result.requestId(), result.code().name(),
                    result.intent() == null ? null : result.intent().name(),
                    answer == null ? null : answer.type().json(), answer == null ? null : answer.text(),
                    answer == null ? List.of() : answer.explanation(),
                    answer == null ? List.of() : answer.financialData(),
                    answer == null ? List.of() : answer.actions(),
                    result.confidence() == null ? null : result.confidence().name(), result.validation().name(),
                    result.retryAfter() == null ? null : result.retryAfter().toSeconds(), result.resetsAt(),
                    result.versions());
        }

        /** Sem a resposta: texto e valores não vão para logs. */
        @Override
        public String toString() {
            return "DiagnosticResponse[" + requestId + ", " + code + "]";
        }
    }

    @PostMapping
    public DiagnosticResponse ask(@PathVariable UUID workspaceId, @RequestBody DiagnosticRequest request) {
        return DiagnosticResponse.from(askCopilot.ask(workspaceId, request == null ? null : request.question()));
    }
}
