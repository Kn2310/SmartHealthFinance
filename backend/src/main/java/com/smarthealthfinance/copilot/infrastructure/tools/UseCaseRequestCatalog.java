package com.smarthealthfinance.copilot.infrastructure.tools;

import com.smarthealthfinance.cards.application.dto.CardsSummaryView;
import com.smarthealthfinance.cards.application.usecase.GetCardsSummary;
import com.smarthealthfinance.copilot.application.port.RequestCatalog;
import com.smarthealthfinance.copilot.domain.model.Pseudonyms;
import com.smarthealthfinance.goals.application.dto.GoalListView;
import com.smarthealthfinance.goals.application.dto.GoalView;
import com.smarthealthfinance.goals.application.usecase.ListGoals;
import com.smarthealthfinance.transactions.application.dto.CategoryView;
import com.smarthealthfinance.transactions.application.usecase.ListCategories;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Catálogo da pergunta (ADR-0039 §4, adendos da Parte C) pelos casos de uso da lista fechada: categorias do sistema
 * ({@link ListCategories}), objetivos abertos ({@link ListGoals}) e cartões ativos ({@link GetCardsSummary}). Os
 * alvos ganham pseudônimo aqui, na mesma ordem das telas, e vão ao modelo de intenção só com tipo, prazo ou bandeira.
 */
@Component
public class UseCaseRequestCatalog implements RequestCatalog {

    /** Quantos alvos de cada tipo o catálogo oferece (as tools cobrem os mesmos). */
    public static final int MAX_TARGETS = 5;

    private final ListCategories categories;
    private final ListGoals goals;
    private final GetCardsSummary cards;

    public UseCaseRequestCatalog(ListCategories categories, ListGoals goals, GetCardsSummary cards) {
        this.categories = categories;
        this.goals = goals;
        this.cards = cards;
    }

    @Override
    public Catalog load(UUID workspaceId, Pseudonyms pseudonyms) {
        Map<String, String> names = new LinkedHashMap<>();
        for (CategoryView category : categories.execute(workspaceId)) {
            names.put(category.code(), category.name());
        }
        List<Target> targets = new ArrayList<>();
        GoalListView goalList = goals.execute(workspaceId, null);
        goalList.goals().stream().limit(MAX_TARGETS).forEach(goal -> targets.add(new Target(
                ToolValues.goal(pseudonyms, goal), "objetivo " + goal.type() + ", prazo "
                + (goal.deadline() == null ? "sem prazo" : goal.deadline()) + ", " + goal.status())));
        CardsSummaryView summary = cards.execute(workspaceId);
        summary.cards().stream().limit(MAX_TARGETS).forEach(card -> targets.add(new Target(
                ToolValues.card(pseudonyms, card.card()), "cartão " + card.card().brand())));
        return new Catalog(goalList.savedTotal().currency(), names, targets);
    }
}
