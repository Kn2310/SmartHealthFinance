package com.smarthealthfinance.cards.application;

import com.smarthealthfinance.cards.domain.model.CreditCard;
import com.smarthealthfinance.cards.domain.repository.CreditCardRepository;
import com.smarthealthfinance.cards.domain.valueobject.CardId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Fake com semântica próxima do adapter JPA: consultas sempre filtradas por Workspace, cópias sem aliasing e version
 * incrementada no save.
 */
public final class InMemoryCreditCardRepository implements CreditCardRepository {

    private final Map<CardId, CreditCard> cards = new LinkedHashMap<>();

    public void store(CreditCard card) {
        cards.put(card.id(), copy(card, card.version()));
    }

    public int size() {
        return cards.size();
    }

    @Override
    public Optional<CreditCard> findById(WorkspaceId workspaceId, CardId cardId) {
        return Optional.ofNullable(cards.get(cardId))
                .filter(card -> card.workspaceId().equals(workspaceId))
                .map(card -> copy(card, card.version()));
    }

    @Override
    public List<CreditCard> findAllByWorkspace(WorkspaceId workspaceId) {
        return cards.values().stream()
                .filter(card -> card.workspaceId().equals(workspaceId))
                .map(card -> copy(card, card.version()))
                .toList();
    }

    @Override
    public void add(CreditCard card) {
        if (cards.containsKey(card.id())) {
            throw new IllegalStateException("add() só insere cartões novos");
        }
        store(card);
    }

    @Override
    public void save(CreditCard card) {
        if (!cards.containsKey(card.id())) {
            throw new IllegalStateException("save() só atualiza cartões existentes");
        }
        cards.put(card.id(), copy(card, card.version() + 1));
    }

    private static CreditCard copy(CreditCard card, long version) {
        return CreditCard.restore(card.id(), card.workspaceId(), card.nickname(), card.institutionName().orElse(null),
                card.brand(), card.last4(), card.limit(), card.cycle(), card.defaultPaymentAccountId().orElse(null),
                card.status(), card.createdAt(), card.updatedAt(), version);
    }
}
