package com.smarthealthfinance.insights.domain.exception;

/** Só um insight ativo pode ser dispensado (ADR-0023 §3): resolvido ou expirado responde {@code 409 INSIGHT_NOT_ACTIVE}. */
public class InsightNotActiveException extends RuntimeException {

    public InsightNotActiveException() {
        super("Insight não está ativo");
    }
}
