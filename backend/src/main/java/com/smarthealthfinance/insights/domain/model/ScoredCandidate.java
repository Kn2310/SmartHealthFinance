package com.smarthealthfinance.insights.domain.model;

import com.smarthealthfinance.insights.domain.enums.Importance;
import java.util.Objects;

/**
 * Candidato pontuado (ADR-0021 §3).
 *
 * @param fingerprint SHA-256 hex de detector, Workspace, período e sujeito (§5)
 * @param impact      pontos I (0–100)
 * @param relevance   Rv (0–100)
 * @param priority    0,6 · I + 0,4 · Rv, inteiro
 */
public record ScoredCandidate(InsightCandidate candidate, String fingerprint, int impact, int relevance,
                              Importance importance, int priority) {

    public ScoredCandidate {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(importance, "importance");
    }
}
