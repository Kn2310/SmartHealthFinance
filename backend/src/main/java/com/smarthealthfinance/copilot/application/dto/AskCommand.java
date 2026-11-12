package com.smarthealthfinance.copilot.application.dto;

import com.smarthealthfinance.copilot.domain.model.EntryContext;

import java.util.List;
import java.util.Optional;

/**
 * Pergunta ao Copilot (ADR-0040 §3, §7): o texto, até 3 perguntas anteriores da própria pessoa (nunca respostas) e o
 * contexto de entrada, opcional. Valida-se depois da admissão, em {@code AskCopilot}.
 */
public record AskCommand(String question, List<PreviousQuestion> previousQuestions, EntryContext context) {

    public AskCommand {
        previousQuestions = previousQuestions == null ? List.of() : List.copyOf(previousQuestions);
    }

    /** A API manda só textos: a intenção das anteriores é desconhecida. */
    public static AskCommand ofTexts(String question, List<String> previousQuestions, EntryContext context) {
        return new AskCommand(question, previousQuestions == null ? List.of() : previousQuestions.stream()
                .map(text -> new PreviousQuestion(text, null)).toList(), context);
    }

    public static AskCommand of(String question) {
        return new AskCommand(question, List.of(), null);
    }

    public Optional<EntryContext> contextValue() {
        return Optional.ofNullable(context);
    }

    /** Sem os textos: perguntas podem ter nomes e valores digitados. */
    @Override
    public String toString() {
        return "AskCommand[" + previousQuestions.size() + " previous, context="
                + (context == null ? "none" : context.kind()) + "]";
    }
}
