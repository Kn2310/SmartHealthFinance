package com.smarthealthfinance.cards.application.service;

import com.smarthealthfinance.cards.application.exception.CardNotFoundException;
import com.smarthealthfinance.cards.domain.model.CreditCard;
import com.smarthealthfinance.cards.domain.repository.CreditCardRepository;
import com.smarthealthfinance.cards.domain.valueobject.CardId;
import com.smarthealthfinance.identity.application.service.CurrentUserService;
import com.smarthealthfinance.identity.application.service.WorkspaceAccessGuard;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Autorização dos casos de uso de cartões: primeiro o Workspace (membership, ADR-0003), depois o cartão — sempre
 * buscado dentro do Workspace autorizado.
 */
@Service
public class CardAccess {

    private final CurrentUserService currentUser;
    private final WorkspaceAccessGuard accessGuard;
    private final CreditCardRepository cards;

    public CardAccess(CurrentUserService currentUser, WorkspaceAccessGuard accessGuard, CreditCardRepository cards) {
        this.currentUser = currentUser;
        this.accessGuard = accessGuard;
        this.cards = cards;
    }

    /** @throws com.smarthealthfinance.identity.application.exception.WorkspaceNotFoundException sem membership */
    public WorkspaceId requireWorkspace(UUID workspaceId) {
        WorkspaceId id = new WorkspaceId(workspaceId);
        accessGuard.requireMember(currentUser.requireCurrentUserId(), id);
        return id;
    }

    /** @throws CardNotFoundException se o cartão não existir dentro do Workspace autorizado */
    public CreditCard requireCard(UUID workspaceId, UUID cardId) {
        return cards.findById(requireWorkspace(workspaceId), new CardId(cardId)).orElseThrow(CardNotFoundException::new);
    }
}
