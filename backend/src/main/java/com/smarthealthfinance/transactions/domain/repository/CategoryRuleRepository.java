package com.smarthealthfinance.transactions.domain.repository;

import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.transactions.domain.enums.CategoryNature;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionCategory;

import java.time.Instant;
import java.util.List;

/** Regras aprendidas das correções do usuário (ADR-0013 §2). Toda operação é escopada pelo Workspace. */
public interface CategoryRuleRepository {

    /** Comerciante (chave normalizada) → categoria, para um Workspace. */
    record LearnedRule(String merchantKey, TransactionCategory category) {
    }

    List<LearnedRule> findAll(WorkspaceId workspaceId);

    /** Cria a regra ou troca a categoria da existente (uma regra por comerciante e natureza). */
    void upsert(WorkspaceId workspaceId, LearnedRule rule, Instant now);

    /**
     * Copia a regra de {@code fromKey} para {@code toKey} (renormalização do comerciante). Se {@code toKey} já tem
     * regra, vence a atualizada por último. Sem regra em {@code fromKey}, nada acontece.
     */
    void copy(WorkspaceId workspaceId, String fromKey, String toKey, CategoryNature nature);

    void delete(WorkspaceId workspaceId, String merchantKey, CategoryNature nature);
}
