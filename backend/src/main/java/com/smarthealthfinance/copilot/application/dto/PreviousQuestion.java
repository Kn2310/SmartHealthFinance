package com.smarthealthfinance.copilot.application.dto;

import com.smarthealthfinance.copilot.domain.enums.Intent;

/**
 * Pergunta anterior da própria pessoa, usada só como continuidade (ADR-0040 §7): o texto e a intenção classificada
 * quando foi feita ({@code null} se desconhecida, como na conversa efêmera). Nunca carrega a resposta.
 */
public record PreviousQuestion(String question, Intent intent) {

    public static PreviousQuestion of(String question) {
        return new PreviousQuestion(question, null);
    }

    /** Sem o texto: perguntas podem ter nomes e valores digitados. */
    @Override
    public String toString() {
        return "PreviousQuestion[" + (intent == null ? "?" : intent) + "]";
    }
}
