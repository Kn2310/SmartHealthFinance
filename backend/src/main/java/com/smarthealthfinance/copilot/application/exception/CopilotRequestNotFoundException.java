package com.smarthealthfinance.copilot.application.exception;

/** Pergunta que não é da pessoa no Workspace (ou inexistente) ao enviar feedback (ADR-0040 §8): 404 {@code COPILOT_REQUEST_NOT_FOUND}. */
public final class CopilotRequestNotFoundException extends RuntimeException {

    public CopilotRequestNotFoundException() {
        super("CopilotRequestNotFoundException");
    }
}
