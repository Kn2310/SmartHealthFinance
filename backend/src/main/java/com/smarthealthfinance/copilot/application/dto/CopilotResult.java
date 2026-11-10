package com.smarthealthfinance.copilot.application.dto;

import com.smarthealthfinance.copilot.domain.enums.AnswerConfidence;
import com.smarthealthfinance.copilot.domain.enums.Intent;
import com.smarthealthfinance.copilot.domain.enums.ResultCode;
import com.smarthealthfinance.copilot.domain.enums.ValidationStatus;
import com.smarthealthfinance.copilot.domain.model.RenderedAnswer;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Resultado de uma pergunta ao Copilot (ADR-0039 §13): o contrato que a API de conversa da Sessão 8.2 expõe.
 * <ul>
 *   <li>{@code answer} existe com {@code OK}, {@code SAFE_MODE}, {@code INSUFFICIENT_DATA} e {@code OUT_OF_SCOPE};</li>
 *   <li>{@code intent} é nulo quando a pergunta não chegou à classificação (admissão recusada ou provedor fora);</li>
 *   <li>{@code confidence} só quando há contexto financeiro;</li>
 *   <li>{@code retryAfter} só com {@code RATE_LIMITED}; {@code resetsAt} só com {@code QUOTA_EXCEEDED};</li>
 *   <li>{@code versions}: prompts, esquemas e algoritmos usados (§11).</li>
 * </ul>
 */
public record CopilotResult(UUID requestId, ResultCode code, Intent intent, RenderedAnswer answer,
                            AnswerConfidence confidence, ValidationStatus validation, Duration retryAfter,
                            Instant resetsAt, Map<String, String> versions) {

    public CopilotResult {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(validation, "validation");
        versions = Map.copyOf(versions);
    }

    public Optional<RenderedAnswer> answerValue() {
        return Optional.ofNullable(answer);
    }

    /** Sem conteúdo: a resposta não vai para logs. */
    @Override
    public String toString() {
        return "CopilotResult[requestId=" + requestId + ", code=" + code + ", intent=" + intent + ", validation="
                + validation + "]";
    }
}
