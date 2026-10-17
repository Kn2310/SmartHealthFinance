package com.smarthealthfinance.insights.domain.model;

import java.util.List;
import java.util.Objects;

/**
 * O que o usuário lê (ADR-0021 §2/§7): título (frase do card e do detalhe), apoio, rótulo do card, linha do
 * "O que mudou?", ação recomendada, evidências, ações e referências. Templates preenchidos no backend; nunca saem em
 * log.
 *
 * @param subjectLabel   o assunto na meta do card ("Alimentação fora", "Reserva"); nulo em insights gravados antes do
 *                       adendo de 2026-10-17 ao ADR-0021, até a próxima geração
 * @param changeLine     só nos detectores de mudança (D1, D2, D3, D5, D6), para o "O que mudou?" (ADR-0023 §5)
 * @param recommendation só D1 tem texto próprio; nos demais é nula e a UI usa o rótulo da ação primária
 */
public record InsightContent(String title, String support, String subjectLabel, ChangeLine changeLine,
                             Recommendation recommendation, InsightEvidence evidence, List<InsightAction> actions,
                             InsightRefs refs) {

    public InsightContent {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(support, "support");
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(refs, "refs");
        actions = List.copyOf(actions);
        if (actions.stream().filter(InsightAction::primary).count() != 1) {
            throw new IllegalArgumentException("Um insight tem exatamente uma ação primária");
        }
    }

    public InsightAction primaryAction() {
        return actions.stream().filter(InsightAction::primary).findFirst().orElseThrow();
    }
}
