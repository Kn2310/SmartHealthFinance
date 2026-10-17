package com.smarthealthfinance.insights.application.exception;

/** Insight inexistente ou de outro Workspace: indistinguíveis de propósito (ADR-0023 §1). */
public class InsightNotFoundException extends RuntimeException {

    public InsightNotFoundException() {
        super("Insight não encontrado");
    }
}
