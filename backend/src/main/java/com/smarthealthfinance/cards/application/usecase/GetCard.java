package com.smarthealthfinance.cards.application.usecase;

import com.smarthealthfinance.cards.application.dto.CardView;
import com.smarthealthfinance.cards.application.service.CardAccess;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class GetCard {

    private final CardAccess access;

    public GetCard(CardAccess access) {
        this.access = access;
    }

    @Transactional(readOnly = true)
    public CardView execute(UUID workspaceId, UUID cardId) {
        return CardView.from(access.requireCard(workspaceId, cardId));
    }
}
