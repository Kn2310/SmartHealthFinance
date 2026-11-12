package com.smarthealthfinance.copilot.application.exception;

/** Provedor fora, circuito aberto, Copilot desligado ou Redis fora (ADR-0040 §4): 503 {@code COPILOT_UNAVAILABLE}. */
public final class CopilotUnavailableException extends RuntimeException {

    public CopilotUnavailableException() {
        super("CopilotUnavailableException");
    }
}
