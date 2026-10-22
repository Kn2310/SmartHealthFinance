package com.smarthealthfinance.cards.application.usecase;

import com.smarthealthfinance.cards.application.dto.CardView;
import com.smarthealthfinance.cards.application.service.CardAccess;
import com.smarthealthfinance.cards.domain.enums.CardStatus;
import com.smarthealthfinance.cards.domain.repository.CreditCardRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ListCards {

    private final CardAccess access;
    private final CreditCardRepository cards;

    public ListCards(CardAccess access, CreditCardRepository cards) {
        this.access = access;
        this.cards = cards;
    }

    @Transactional(readOnly = true)
    public List<CardView> execute(UUID workspaceId, boolean includeArchived) {
        return cards.findAllByWorkspace(access.requireWorkspace(workspaceId)).stream()
                .filter(card -> includeArchived || card.status() == CardStatus.ACTIVE)
                .map(CardView::from)
                .toList();
    }
}
