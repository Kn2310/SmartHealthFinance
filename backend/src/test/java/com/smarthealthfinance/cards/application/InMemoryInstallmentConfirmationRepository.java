package com.smarthealthfinance.cards.application;

import com.smarthealthfinance.cards.domain.repository.InstallmentConfirmationRepository;
import com.smarthealthfinance.cards.domain.valueobject.CardId;
import com.smarthealthfinance.cards.domain.valueobject.InstallmentKey;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Como a tabela: por Workspace e cartão, e a mesma parcela nunca é confirmada duas vezes. */
public final class InMemoryInstallmentConfirmationRepository implements InstallmentConfirmationRepository {

    private final Map<InstallmentKey, CardId> confirmed = new HashMap<>();
    private final Map<InstallmentKey, WorkspaceId> owners = new HashMap<>();

    @Override
    public Set<InstallmentKey> findByCard(WorkspaceId workspaceId, CardId cardId) {
        Set<InstallmentKey> keys = new HashSet<>();
        confirmed.forEach((key, card) -> {
            if (card.equals(cardId) && owners.get(key).equals(workspaceId)) {
                keys.add(key);
            }
        });
        return keys;
    }

    @Override
    public void addAll(WorkspaceId workspaceId, CardId cardId, Collection<InstallmentKey> keys, Instant now) {
        for (InstallmentKey key : keys) {
            if (confirmed.putIfAbsent(key, cardId) != null) {
                throw new IllegalStateException("Parcela já confirmada");
            }
            owners.put(key, workspaceId);
        }
    }
}
