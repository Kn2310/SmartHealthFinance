package com.smarthealthfinance.copilot.application.port;

import java.util.Objects;

/**
 * Prompts e esquemas versionados (ADR-0039 §11), carregados no boot de {@code resources/copilot}. Mudar o texto de um
 * prompt exige um arquivo novo ({@code -v2}); o hash fica na versão registrada no ledger.
 */
public interface CopilotPrompts {

    Versioned intentPrompt();

    Versioned answerPrompt();

    Versioned intentSchema();

    Versioned answerSchema();

    /** Um arquivo versionado: {@code name} (ex.: {@code answer-v1}), conteúdo e SHA-256 em hexadecimal. */
    record Versioned(String name, String text, String sha256) {

        public Versioned {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(sha256, "sha256");
        }

        /** {@code answer-v1@3fa1c2d9}: nome e os 8 primeiros caracteres do hash. */
        public String version() {
            return name + "@" + sha256.substring(0, 8);
        }

        @Override
        public String toString() {
            return "Versioned[" + version() + "]";
        }
    }
}
