package com.smarthealthfinance.copilot.application.port;

import com.smarthealthfinance.copilot.domain.model.Pseudonyms;

import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Catálogo de uma pergunta, montado antes da intenção (ADR-0039 §4, adendo da Parte C): a moeda do Workspace, o
 * catálogo de categorias do sistema e os alvos pseudonimizados (objetivos com tipo e prazo, cartões com a bandeira)
 * que o modelo de intenção pode escolher. Nomes digitados pela pessoa ficam só no {@link Pseudonyms}.
 */
public interface RequestCatalog {

    Catalog load(UUID workspaceId, Pseudonyms pseudonyms);

    /** Um alvo como vai ao modelo de intenção: pseudônimo e descrição sem dado pessoal. */
    record Target(String pseudonym, String description) {}

    record Catalog(Currency currency, Map<String, String> categoryNames, List<Target> targets) {

        public Catalog {
            Objects.requireNonNull(currency, "currency");
            categoryNames = Map.copyOf(categoryNames);
            targets = List.copyOf(targets);
        }

        /** Só tamanhos. */
        @Override
        public String toString() {
            return "Catalog[" + categoryNames.size() + " categories, " + targets.size() + " targets]";
        }
    }
}
