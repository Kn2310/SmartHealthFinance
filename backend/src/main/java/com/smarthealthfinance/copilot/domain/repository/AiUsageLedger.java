package com.smarthealthfinance.copilot.domain.repository;

import com.smarthealthfinance.copilot.domain.model.AiUsage;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;

import com.smarthealthfinance.identity.domain.valueobject.UserId;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Usage ledger (ADR-0039 §10), só de inserção; é também a fonte das quotas (o Redis nunca é fonte da verdade). */
public interface AiUsageLedger {

    void append(AiUsage usage);

    /**
     * Perguntas = chamadas de intenção registradas no Workspace em {@code [from, to)}, exceto as que falharam do lado
     * do provedor ({@code UNAVAILABLE}, {@code TIMEOUT}, {@code RATE_LIMITED}): queda do provedor não consome quota
     * (adendo ao ADR-0039 §10, 2026-11-10). Recusa e saída inválida contam.
     */
    long countQuestions(WorkspaceId workspaceId, Instant from, Instant to);

    /**
     * Versões de prompt registradas para uma pergunta da pessoa no Workspace ({@code intentPrompt}, {@code
     * answerPrompt}), ou vazio se o ledger não tem esse {@code requestId} para ela (ADR-0040 §8). Nunca conteúdo.
     */
    Optional<Map<String, String>> findRequestVersions(WorkspaceId workspaceId, UserId userId, UUID requestId);

    /** Tokens de entrada (sem cache + leitura e escrita de cache) no Workspace em {@code [from, to)}. */
    long sumInputTokens(WorkspaceId workspaceId, Instant from, Instant to);
}
