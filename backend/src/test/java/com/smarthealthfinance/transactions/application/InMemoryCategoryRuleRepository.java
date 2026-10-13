package com.smarthealthfinance.transactions.application;

import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.transactions.domain.repository.CategoryRuleRepository;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Regras aprendidas em memória, sempre filtradas pelo Workspace (uma por comerciante e natureza). */
public final class InMemoryCategoryRuleRepository implements CategoryRuleRepository {

    private record Key(WorkspaceId workspaceId, String merchantKey, String nature) {
    }

    private final Map<Key, LearnedRule> rules = new LinkedHashMap<>();

    @Override
    public List<LearnedRule> findAll(WorkspaceId workspaceId) {
        return rules.entrySet().stream()
                .filter(entry -> entry.getKey().workspaceId().equals(workspaceId))
                .map(Map.Entry::getValue)
                .toList();
    }

    @Override
    public void upsert(WorkspaceId workspaceId, LearnedRule rule, Instant now) {
        rules.put(new Key(workspaceId, rule.merchantKey(), rule.category().nature().name()), rule);
    }
}
