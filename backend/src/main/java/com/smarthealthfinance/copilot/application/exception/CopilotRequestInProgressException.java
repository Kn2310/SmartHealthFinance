package com.smarthealthfinance.copilot.application.exception;

/** Replay de uma chave ainda em processamento (ADR-0040 §5): 409 {@code COPILOT_REQUEST_IN_PROGRESS}. */
public final class CopilotRequestInProgressException extends RuntimeException {

    public CopilotRequestInProgressException() {
        super("CopilotRequestInProgressException");
    }
}
