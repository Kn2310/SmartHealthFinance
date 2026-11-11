package com.smarthealthfinance.copilot.application.exception;

/** O alvo do contexto de entrada não existe no Workspace (ADR-0040 §3): vira 404 {@code COPILOT_CONTEXT_NOT_FOUND}. */
public final class ContextNotFoundException extends RuntimeException {

    public ContextNotFoundException() {
        super("Copilot entry context not found");
    }
}
