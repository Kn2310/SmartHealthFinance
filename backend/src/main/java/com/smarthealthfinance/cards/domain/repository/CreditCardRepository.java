package com.smarthealthfinance.cards.domain.repository;

import com.smarthealthfinance.cards.domain.model.CreditCard;
import com.smarthealthfinance.cards.domain.valueobject.CardId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;

import java.util.List;
import java.util.Optional;

public interface CreditCardRepository {

    Optional<CreditCard> findById(WorkspaceId workspaceId, CardId cardId);

    /** Em ordem de criação. */
    List<CreditCard> findAllByWorkspace(WorkspaceId workspaceId);

    void add(CreditCard card);

    void save(CreditCard card);
}
