package com.smarthealthfinance.copilot.domain.model;

import com.smarthealthfinance.copilot.domain.enums.AiOutcome;
import com.smarthealthfinance.copilot.domain.enums.AiRequestType;
import com.smarthealthfinance.copilot.domain.valueobject.TokenUsage;
import com.smarthealthfinance.identity.domain.valueobject.UserId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Linha do usage ledger (ADR-0039 §10): quem, o quê, quanto e quão rápido. Nunca conteúdo de pergunta, contexto ou
 * resposta. {@code requestId} agrupa as chamadas de uma mesma pergunta; {@code promptVersion}
 * ({@code answer-v1@3fa1c2d9}) e {@code schemaVersion} identificam o que foi enviado (§11), vazios quando não se
 * aplicam.
 */
public record AiUsage(UUID id, WorkspaceId workspaceId, UserId userId, UUID requestId, AiRequestType requestType,
                      String provider, String model, TokenUsage tokens, BigDecimal cost, String pricingVersion,
                      String promptVersion, String schemaVersion, long latencyMs, AiOutcome outcome,
                      Instant createdAt) {

    public AiUsage {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(requestType, "requestType");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(tokens, "tokens");
        Objects.requireNonNull(cost, "cost");
        Objects.requireNonNull(pricingVersion, "pricingVersion");
        Objects.requireNonNull(promptVersion, "promptVersion");
        Objects.requireNonNull(schemaVersion, "schemaVersion");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(createdAt, "createdAt");
        if (cost.signum() < 0 || latencyMs < 0) {
            throw new IllegalArgumentException("cost and latency must not be negative");
        }
    }
}
