package com.smarthealthfinance.copilot.infrastructure.ai;

import com.smarthealthfinance.copilot.domain.enums.AiOutcome;

import java.util.Objects;

/**
 * Falha do provedor que conta para o circuit breaker (ADR-0039 §2). {@code retryable}: só 429, 5xx e erro de conexão;
 * timeout de leitura não é repetido (estouraria o orçamento do pipeline). A mensagem nunca carrega conteúdo.
 */
public final class AiProviderException extends RuntimeException {

    private final AiOutcome outcome;
    private final boolean retryable;

    public AiProviderException(AiOutcome outcome, boolean retryable, String reason) {
        super(reason);
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        this.retryable = retryable;
    }

    public AiOutcome outcome() {
        return outcome;
    }

    public boolean retryable() {
        return retryable;
    }
}
