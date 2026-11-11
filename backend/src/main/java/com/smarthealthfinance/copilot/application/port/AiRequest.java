package com.smarthealthfinance.copilot.application.port;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Uma chamada ao modelo: prompt de sistema versionado, conteúdo do usuário (pergunta e contexto já minimizados, ADR-0039
 * §3) e o JSON Schema da saída estruturada. Nada disso é logado. {@code deadline}, quando presente, é o fim do
 * orçamento do pipeline (§2): cada tentativa, inclusive o retry, usa o menor entre o que resta até ele e o timeout do
 * papel, e nenhuma começa depois dele.
 */
public record AiRequest(String systemPrompt, String userContent, String jsonSchema, Instant deadline) {

    public AiRequest {
        Objects.requireNonNull(systemPrompt, "systemPrompt");
        Objects.requireNonNull(userContent, "userContent");
        Objects.requireNonNull(jsonSchema, "jsonSchema");
    }

    /** Sem prazo além do timeout do papel. */
    public AiRequest(String systemPrompt, String userContent, String jsonSchema) {
        this(systemPrompt, userContent, jsonSchema, null);
    }

    public Optional<Instant> deadlineValue() {
        return Optional.ofNullable(deadline);
    }

    /** Sem conteúdo: o toString de um record exporia o prompt em logs e exceções. */
    @Override
    public String toString() {
        return "AiRequest[systemPrompt=" + systemPrompt.length() + " chars, userContent=" + userContent.length()
                + " chars]";
    }
}
