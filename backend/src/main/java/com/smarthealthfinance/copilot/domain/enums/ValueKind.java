package com.smarthealthfinance.copilot.domain.enums;

/** Natureza de um valor do contexto (ADR-0039 §5): estimados e simulados sempre aparecem com rótulo (§7). */
public enum ValueKind {
    FACT,
    ESTIMATED,
    SIMULATED;

    /** Rótulo pt-BR acrescentado pelo sistema ao valor renderizado; fato não tem rótulo. */
    public String label() {
        return switch (this) {
            case FACT -> "";
            case ESTIMATED -> "estimado";
            case SIMULATED -> "simulado";
        };
    }
}
