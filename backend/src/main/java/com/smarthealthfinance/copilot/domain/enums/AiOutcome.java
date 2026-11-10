package com.smarthealthfinance.copilot.domain.enums;

/**
 * Resultado tipado de uma chamada ao provedor (ADR-0039 §2). Nenhuma falha de IA vira exceção para quem chama:
 * {@code UNAVAILABLE} cobre provedor fora, circuito aberto e limite de concorrência.
 */
public enum AiOutcome {
    OK,
    REFUSED,
    TIMEOUT,
    UNAVAILABLE,
    RATE_LIMITED,
    INVALID_OUTPUT
}
