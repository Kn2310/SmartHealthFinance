package com.smarthealthfinance.copilot.domain.model;

import com.smarthealthfinance.copilot.domain.enums.Intent;

import java.time.YearMonth;
import java.util.Objects;
import java.util.Optional;

/**
 * Intenção e parâmetros escolhidos pelo modelo leve, já conferidos pelo pipeline (ADR-0039 §4): o modelo só escolhe;
 * o pipeline decide e executa as tools. Parâmetro inválido vira nulo, nunca erro. {@code target} é um pseudônimo do
 * catálogo da requisição; {@code category} é um código do catálogo de categorias.
 */
public record IntentDecision(Intent intent, YearMonth month, String category, String target, Scenario scenario) {

    /**
     * Cenário de simulação de objetivo (ADR-0036), com os valores que a pessoa escreveu na pergunta. Valores em
     * decimal simples; meses em {@code YYYY-MM}. Ao menos um campo presente.
     */
    public record Scenario(String monthlyAmount, String deadline, String reductionCategory, String reductionAmount) {

        public boolean empty() {
            return monthlyAmount == null && deadline == null && reductionCategory == null && reductionAmount == null;
        }

        /** Sem os valores que a pessoa escreveu. */
        @Override
        public String toString() {
            return "Scenario[...]";
        }
    }

    /** Sem os parâmetros: valores e alvo não vão para logs. */
    @Override
    public String toString() {
        return "IntentDecision[" + intent + "]";
    }

    public IntentDecision {
        Objects.requireNonNull(intent, "intent");
        if (scenario != null && scenario.empty()) {
            scenario = null;
        }
    }

    public static IntentDecision of(Intent intent) {
        return new IntentDecision(intent, null, null, null, null);
    }

    public Optional<YearMonth> monthValue() {
        return Optional.ofNullable(month);
    }

    public Optional<String> categoryValue() {
        return Optional.ofNullable(category);
    }

    public Optional<String> targetValue() {
        return Optional.ofNullable(target);
    }

    public Optional<Scenario> scenarioValue() {
        return Optional.ofNullable(scenario);
    }
}
