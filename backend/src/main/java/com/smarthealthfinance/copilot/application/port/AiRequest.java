package com.smarthealthfinance.copilot.application.port;

import java.util.Objects;

/**
 * Uma chamada ao modelo: prompt de sistema versionado, conteúdo do usuário (pergunta e contexto já minimizados, ADR-0039
 * §3) e o JSON Schema da saída estruturada. Nada disso é logado.
 */
public record AiRequest(String systemPrompt, String userContent, String jsonSchema) {

    public AiRequest {
        Objects.requireNonNull(systemPrompt, "systemPrompt");
        Objects.requireNonNull(userContent, "userContent");
        Objects.requireNonNull(jsonSchema, "jsonSchema");
    }

    /** Sem conteúdo: o toString de um record exporia o prompt em logs e exceções. */
    @Override
    public String toString() {
        return "AiRequest[systemPrompt=" + systemPrompt.length() + " chars, userContent=" + userContent.length()
                + " chars]";
    }
}
