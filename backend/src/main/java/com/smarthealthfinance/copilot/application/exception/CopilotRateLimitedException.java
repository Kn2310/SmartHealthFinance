package com.smarthealthfinance.copilot.application.exception;

import java.time.Duration;

/** Rate limit por pessoa (ADR-0040 §4): 429 {@code COPILOT_RATE_LIMITED} com {@code Retry-After}. */
public final class CopilotRateLimitedException extends RuntimeException {

    private final Duration retryAfter;

    public CopilotRateLimitedException(Duration retryAfter) {
        super("CopilotRateLimitedException");
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
