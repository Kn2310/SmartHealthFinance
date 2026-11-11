package com.smarthealthfinance.copilot.domain.enums;

/** Como a resposta passou pela validação (ADR-0039 §7): direto, depois de regenerar, ou em modo seguro. */
public enum ValidationStatus {
    VALID,
    REGENERATED,
    SAFE_MODE,
    NOT_APPLICABLE
}
