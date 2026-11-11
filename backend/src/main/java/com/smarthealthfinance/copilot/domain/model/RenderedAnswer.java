package com.smarthealthfinance.copilot.domain.model;

import com.smarthealthfinance.copilot.domain.enums.ActionKind;
import com.smarthealthfinance.copilot.domain.enums.AnswerType;
import com.smarthealthfinance.copilot.domain.enums.ValueKind;

import java.util.List;
import java.util.Objects;

/**
 * Resposta pronta para a pessoa (ADR-0039 §6–§7): refs trocadas pelos valores formatados em pt-BR, pseudônimos pelos
 * nomes que ela conhece e ações resolvidas para os ids reais. Nunca volta ao modelo.
 */
public record RenderedAnswer(AnswerType type, String text, List<String> explanation, List<Value> financialData,
                             List<Action> actions) {

    /** Um valor citado, como a tela mostra. */
    public record Value(String ref, String label, String formatted, ValueKind kind, String period, String source) {

        /** Sem rótulo nem valor. */
        @Override
        public String toString() {
            return "Value[" + ref + ", " + kind + "]";
        }
    }

    /** Link para uma tela; {@code targetId} é o id interno (UUID ou código), nulo para telas do Workspace. */
    public record Action(ActionKind kind, String targetId, String targetName) {

        /** Sem o nome digitado pela pessoa. */
        @Override
        public String toString() {
            return "Action[" + kind + "]";
        }
    }

    public RenderedAnswer {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(text, "text");
        explanation = List.copyOf(explanation);
        financialData = List.copyOf(financialData);
        actions = List.copyOf(actions);
    }

    /** Sem conteúdo: a resposta não vai para logs. */
    @Override
    public String toString() {
        return "RenderedAnswer[type=" + type + ", " + text.length() + " chars]";
    }
}
