package com.smarthealthfinance.copilot.application.port;

import com.smarthealthfinance.copilot.application.dto.PreviousQuestion;
import com.smarthealthfinance.copilot.domain.model.AnswerDraft;
import com.smarthealthfinance.copilot.domain.model.CopilotContext;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Serialização do que vai ao modelo e leitura do que volta (ADR-0039 §5, §6, §8), fora da camada de aplicação. A
 * pergunta e os dados vão separados: dados só como JSON dentro de {@code <dados_financeiros>}, nunca no prompt de
 * sistema. Ler devolve vazio para qualquer JSON fora do esquema (Structured Output Validation).
 */
public interface CopilotCodec {

    /** Conteúdo de usuário da chamada de intenção: data de hoje, catálogo e pergunta. */
    default String intentInput(String question, LocalDate today, RequestCatalog.Catalog catalog) {
        return intentInput(question, List.of(), today, catalog);
    }

    /**
     * Conteúdo de usuário da chamada de intenção com as perguntas anteriores da pessoa (ADR-0040 §7), em um bloco
     * {@code <perguntas_anteriores>} delimitado. Cada uma com a intenção classificada, quando conhecida, como dado de contexto. Só perguntas, nunca respostas.
     */
    String intentInput(String question, List<PreviousQuestion> previousQuestions, LocalDate today,
                       RequestCatalog.Catalog catalog);

    /**
     * Conteúdo de usuário da chamada de resposta: pergunta, contexto {@code context-v1} e, na regeneração, os códigos
     * das violações da tentativa anterior.
     */
    default String answerInput(String question, CopilotContext context, List<String> previousViolations) {
        return answerInput(question, List.of(), context, previousViolations);
    }

    /** Como {@link #answerInput(String, CopilotContext, List)}, com as perguntas anteriores da pessoa (ADR-0040 §7). */
    String answerInput(String question, List<PreviousQuestion> previousQuestions, CopilotContext context,
                       List<String> previousViolations);

    Optional<RawIntent> readIntent(String json);

    Optional<AnswerDraft> readAnswer(String json);

    /** A intenção como o modelo escreveu, ainda não conferida contra o catálogo. */
    record RawIntent(String intent, String month, String category, String target, String monthlyAmount,
                     String deadline, String reductionCategory, String reductionAmount) {

        @Override
        public String toString() {
            return "RawIntent[" + intent + "]";
        }
    }

    /** Tamanho estimado em tokens (caracteres ÷ 4), o mesmo critério do provedor simulado. */
    static long estimateTokens(String text) {
        return (text.length() + 3) / 4;
    }
}
