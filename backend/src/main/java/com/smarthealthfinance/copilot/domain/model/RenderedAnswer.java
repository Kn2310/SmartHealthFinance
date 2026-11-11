package com.smarthealthfinance.copilot.domain.model;

import com.smarthealthfinance.copilot.domain.enums.ActionKind;
import com.smarthealthfinance.copilot.domain.enums.AnswerType;
import com.smarthealthfinance.copilot.domain.enums.ValueKind;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Resposta pronta para a pessoa (ADR-0039 §6–§7, ADR-0040 §3): refs trocadas pelos valores formatados em pt-BR,
 * pseudônimos pelos nomes que ela conhece e ações resolvidas para os ids reais. Nunca volta ao modelo.
 *
 * <p>{@code text} e {@code explanation} são o texto plano (modo seguro e "Copiar"); {@code segments} e
 * {@code explanationSegments} são os mesmos textos como trechos tipados, gerados na mesma passada. {@code breakdown} e
 * {@code dataUsed} são montados pelo sistema, nunca pelo modelo.
 */
public record RenderedAnswer(AnswerType type, String text, List<AnswerSegment> segments, List<String> explanation,
                             List<List<AnswerSegment>> explanationSegments, List<Value> financialData,
                             List<Action> actions, Breakdown breakdown, List<DataUsedItem> dataUsed) {

    /** Um valor citado, como a tela mostra. */
    public record Value(String ref, String label, String formatted, ValueKind kind, String period, String source) {

        /** Sem rótulo nem valor. */
        @Override
        public String toString() {
            return "Value[" + ref + ", " + kind + "]";
        }
    }

    /**
     * Link para uma tela; {@code targetId} é o id interno (UUID ou código), nulo para telas do Workspace. {@code label}
     * é o texto fixo do botão e {@code params} os parâmetros da tela (ex.: {@code month}).
     */
    public record Action(ActionKind kind, String targetId, String targetName, Map<String, String> params) {

        public Action {
            Objects.requireNonNull(kind, "kind");
            params = params == null ? Map.of() : Map.copyOf(params);
        }

        public Action(ActionKind kind, String targetId, String targetName) {
            this(kind, targetId, targetName, Map.of());
        }

        public String label() {
            return kind.label();
        }

        /** Sem o nome digitado pela pessoa. */
        @Override
        public String toString() {
            return "Action[" + kind + "]";
        }
    }

    public RenderedAnswer {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(text, "text");
        segments = List.copyOf(segments);
        explanation = List.copyOf(explanation);
        explanationSegments = explanationSegments.stream().map(List::copyOf).toList();
        if (explanationSegments.size() != explanation.size()) {
            throw new IllegalArgumentException("explanation and its segments must have the same size");
        }
        financialData = List.copyOf(financialData);
        actions = List.copyOf(actions);
        dataUsed = List.copyOf(dataUsed);
    }

    /** Resposta só de texto fixo, sem valores nem passos. */
    public static RenderedAnswer plain(AnswerType type, String text, List<Action> actions) {
        return new RenderedAnswer(type, text, List.of(new AnswerSegment.Text(text)), List.of(), List.of(), List.of(),
                actions, null, List.of());
    }

    /** Com o quadro e os valores que ele cita (os que ainda não estavam na lista) e a lista "Dados usados". */
    public RenderedAnswer withSystemParts(Breakdown newBreakdown, List<Value> extraValues,
                                          List<DataUsedItem> newDataUsed) {
        List<Value> all = new java.util.ArrayList<>(financialData);
        for (Value extra : extraValues) {
            if (all.stream().noneMatch(value -> value.ref().equals(extra.ref()))) {
                all.add(extra);
            }
        }
        return new RenderedAnswer(type, text, segments, explanation, explanationSegments, all, actions, newBreakdown,
                newDataUsed);
    }

    /** Sem conteúdo: a resposta não vai para logs. */
    @Override
    public String toString() {
        return "RenderedAnswer[type=" + type + ", " + text.length() + " chars]";
    }
}
