package com.smarthealthfinance.copilot.application.dto;

import java.time.Instant;
import java.util.UUID;

/** Mensagem a gravar: a pergunta normalizada e o resultado; expira 90 dias depois de criada (ADR-0040 §6). */
public record NewMessage(UUID id, String question, CopilotResult result, Instant createdAt, Instant expiresAt) {

    /** Sem a pergunta nem a resposta. */
    @Override
    public String toString() {
        return "NewMessage[" + id + "]";
    }
}
