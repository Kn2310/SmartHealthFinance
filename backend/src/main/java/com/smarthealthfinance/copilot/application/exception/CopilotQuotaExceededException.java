package com.smarthealthfinance.copilot.application.exception;

import java.time.Instant;

/** Quota do Workspace esgotada (ADR-0040 §4): 429 {@code COPILOT_QUOTA_EXCEEDED} com {@code resetsAt} no corpo. */
public final class CopilotQuotaExceededException extends RuntimeException {

    private final Instant resetsAt;

    public CopilotQuotaExceededException(Instant resetsAt) {
        super("CopilotQuotaExceededException");
        this.resetsAt = resetsAt;
    }

    public Instant resetsAt() {
        return resetsAt;
    }
}
