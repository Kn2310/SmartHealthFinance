package com.smarthealthfinance.transactions.application;

import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.transactions.domain.enums.CategoryNature;
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
    private final Map<Key, Instant> updatedAt = new LinkedHashMap<>();

    @Override
    public List<LearnedRule> findAll(WorkspaceId workspaceId) {
        return rules.entrySet().stream()
                .filter(entry -> entry.getKey().workspaceId().equals(workspaceId))
                .map(Map.Entry::getValue)
                .toList();
    }

    @Override
    public void upsert(WorkspaceId workspaceId, LearnedRule rule, Instant now) {
        Key key = new Key(workspaceId, rule.merchantKey(), rule.category().nature().name());
        rules.put(key, rule);
        updatedAt.put(key, now);
    }

    /** Mesma regra do SQL: copia para a chave nova; se ela já tem regra, vence a atualizada por último. */
    @Override
    public void copy(WorkspaceId workspaceId, String fromKey, String toKey, CategoryNature nature) {
        Key from = new Key(workspaceId, fromKey, nature.name());
        Key to = new Key(workspaceId, toKey, nature.name());
        LearnedRule source = rules.get(from);
        if (source == null || (rules.containsKey(to) && !updatedAt.get(to).isBefore(updatedAt.get(from)))) {
            return;
        }
        rules.put(to, new LearnedRule(toKey, source.category()));
        updatedAt.put(to, updatedAt.get(from));
    }

    @Override
    public void delete(WorkspaceId workspaceId, String merchantKey, CategoryNature nature) {
        Key key = new Key(workspaceId, merchantKey, nature.name());
        rules.remove(key);
        updatedAt.remove(key);
    }
}
