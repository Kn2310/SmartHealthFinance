package com.smarthealthfinance.insights.domain.model;

import java.util.List;
import java.util.Objects;

/**
 * O que o usuário lê (ADR-0021 §2/§7): título (frase do card e do detalhe), apoio, ação recomendada, evidências,
 * ações e referências. Templates preenchidos no backend; nunca saem em log.
 *
 * @param recommendation só D1 tem texto próprio; nos demais é nula e a UI usa o rótulo da ação primária
 */
public record InsightContent(String title, String support, Recommendation recommendation, InsightEvidence evidence,
                             List<InsightAction> actions, InsightRefs refs) {

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
}
