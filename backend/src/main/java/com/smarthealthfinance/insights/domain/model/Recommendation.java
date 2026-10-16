package com.smarthealthfinance.insights.domain.model;

import java.util.Objects;

/** Bloco "Ação recomendada" (ADR-0021 §2): sugere, não manda. */
public record Recommendation(String title, String text) {

    public Recommendation {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(text, "text");
    }
}
