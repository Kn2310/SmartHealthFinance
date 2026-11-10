package com.smarthealthfinance.copilot.domain.enums;

import java.util.Locale;
import java.util.Optional;

/** Tipo da resposta no esquema {@code answer-v1} (ADR-0039 §6); no JSON, em minúsculas. */
public enum AnswerType {
    ANALYSIS,
    EXPLANATION,
    COMPARISON,
    SIMULATION,
    OUT_OF_SCOPE,
    INSUFFICIENT_DATA;

    public String json() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<AnswerType> fromJson(String value) {
        for (AnswerType type : values()) {
            if (type.json().equals(value)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
