package com.smarthealthfinance.copilot.domain.model;

import java.util.Objects;

/** Origem de um conjunto de valores: o caso de uso usado como tool e a versão do algoritmo dele (ADR-0039 §5). */
public record SourceRef(String tool, String version) {

    public SourceRef {
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(version, "version");
    }
}
