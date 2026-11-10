package com.smarthealthfinance.copilot.domain.model;

import java.util.List;
import java.util.Objects;

/**
 * Saída do modelo de resposta já lida do JSON {@code answer-v1} e ainda não validada (ADR-0039 §6). A
 * {@code confidence} e as {@code sources} declaradas pelo modelo são ignoradas: o sistema calcula as duas.
 * {@code type} fica como texto até a validação.
 */
public record AnswerDraft(String answer, String type, List<String> financialData, List<String> explanation,
                          List<Action> actions) {

    /** Ação como o modelo escreveu: {@code kind} e {@code target} ainda não conferidos. */
    public record Action(String kind, String target) {

        /** Só o tipo: o resto é texto do modelo. */
        @Override
        public String toString() {
            return "Action[" + (kind == null ? "?" : kind.length()) + " chars]";
        }
    }

    public AnswerDraft {
        Objects.requireNonNull(answer, "answer");
        Objects.requireNonNull(type, "type");
        financialData = List.copyOf(financialData);
        explanation = List.copyOf(explanation);
        actions = List.copyOf(actions);
    }

    /** Sem conteúdo: a saída do modelo não vai para logs. */
    @Override
    public String toString() {
        return "AnswerDraft[" + answer.length() + " chars, " + explanation.size() + " steps]";
    }
}
