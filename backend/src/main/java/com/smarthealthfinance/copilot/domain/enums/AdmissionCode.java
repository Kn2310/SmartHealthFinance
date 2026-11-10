package com.smarthealthfinance.copilot.domain.enums;

/**
 * Decisão de admissão de uma pergunta, antes de qualquer dado ir ao provedor (ADR-0039 §10, §13).
 * {@code UNAVAILABLE}: Copilot desligado ou rate limit indisponível (falha fechada).
 */
public enum AdmissionCode {
    ADMITTED,
    CONSENT_REQUIRED,
    RATE_LIMITED,
    QUOTA_EXCEEDED,
    UNAVAILABLE
}
