package com.smarthealthfinance.copilot.domain.enums;

/**
 * Códigos de resultado do pipeline (ADR-0039 §13). {@code VALIDATION_FAILED} é só interno e nunca sai: vira
 * {@code SAFE_MODE}.
 */
public enum ResultCode {
    OK,
    SAFE_MODE,
    CONSENT_REQUIRED,
    RATE_LIMITED,
    QUOTA_EXCEEDED,
    INSUFFICIENT_DATA,
    OUT_OF_SCOPE,
    PROVIDER_UNAVAILABLE
}
