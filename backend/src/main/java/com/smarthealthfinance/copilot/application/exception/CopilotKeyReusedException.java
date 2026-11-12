package com.smarthealthfinance.copilot.application.exception;

/** A mesma Idempotency-Key com outro conteúdo (ADR-0040 §5): 422 {@code IDEMPOTENCY_KEY_REUSED}. */
public final class CopilotKeyReusedException extends RuntimeException {

    public CopilotKeyReusedException() {
        super("CopilotKeyReusedException");
    }
}
