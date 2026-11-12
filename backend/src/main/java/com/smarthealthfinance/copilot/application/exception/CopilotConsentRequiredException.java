package com.smarthealthfinance.copilot.application.exception;

/** Sem consentimento {@code COPILOT_DATA_USE} ativo (ADR-0040 §4): 403 {@code COPILOT_CONSENT_REQUIRED}. */
public final class CopilotConsentRequiredException extends RuntimeException {

    public CopilotConsentRequiredException() {
        super("CopilotConsentRequiredException");
    }
}
