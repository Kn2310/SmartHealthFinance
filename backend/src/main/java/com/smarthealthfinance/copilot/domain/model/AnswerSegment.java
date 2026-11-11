package com.smarthealthfinance.copilot.domain.model;

import java.util.Objects;

/**
 * Trecho tipado de uma resposta renderizada (ADR-0040 §3): texto ou valor. Sem HTML nem marcação: o valor é resolvido
 * pela UI por {@code ref} na lista de valores da resposta.
 */
public sealed interface AnswerSegment {

    /** Texto comum; pode incluir o nome que a pessoa conhece no lugar de um pseudônimo. */
    record Text(String text) implements AnswerSegment {

        public Text {
            Objects.requireNonNull(text, "text");
        }

        /** Sem o texto: pode ter nomes digitados pela pessoa. */
        @Override
        public String toString() {
            return "Text[" + text.length() + " chars]";
        }
    }

    /** Um valor do contexto, pelo ref (ex.: {@code v3}). */
    record Value(String ref) implements AnswerSegment {

        public Value {
            Objects.requireNonNull(ref, "ref");
        }
    }

    /** Discriminador no JSON da API. */
    default String kind() {
        return this instanceof Text ? "TEXT" : "VALUE";
    }
}
