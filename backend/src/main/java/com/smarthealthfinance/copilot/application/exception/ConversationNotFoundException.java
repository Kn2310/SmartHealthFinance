package com.smarthealthfinance.copilot.application.exception;

/** Conversa inexistente, de outra pessoa, expirada ou sem {@code COPILOT_HISTORY} (ADR-0040 §4): 404 {@code COPILOT_CONVERSATION_NOT_FOUND}. */
public final class ConversationNotFoundException extends RuntimeException {

    public ConversationNotFoundException() {
        super("ConversationNotFoundException");
    }
}
