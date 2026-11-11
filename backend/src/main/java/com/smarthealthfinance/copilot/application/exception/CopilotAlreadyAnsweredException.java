package com.smarthealthfinance.copilot.application.exception;

/** Replay de uma pergunta já respondida numa conversa efêmera (ADR-0040 §5): 409 {@code COPILOT_ALREADY_ANSWERED}, sem cobrar de novo. */
public final class CopilotAlreadyAnsweredException extends RuntimeException {

    public CopilotAlreadyAnsweredException() {
        super("CopilotAlreadyAnsweredException");
    }
}
