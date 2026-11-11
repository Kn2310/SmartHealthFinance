package com.smarthealthfinance.copilot.application.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Uma pergunta e a resposta renderizada guardadas no histórico (ADR-0040 §6): uma foto daquele momento, só para
 * exibir. O estado financeiro nunca é relido daqui.
 */
public record StoredMessage(UUID id, UUID conversationId, String question, Instant createdAt, CopilotResult result) {

    /** Sem a pergunta nem a resposta. */
    @Override
    public String toString() {
        return "StoredMessage[" + id + ", conversation=" + conversationId + "]";
    }
}
