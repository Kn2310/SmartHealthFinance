package com.smarthealthfinance.cards.application.usecase;

import com.smarthealthfinance.cards.application.dto.CardView;
import com.smarthealthfinance.cards.application.service.CardAccess;
import com.smarthealthfinance.cards.application.service.CardEvents;
import com.smarthealthfinance.cards.domain.model.CreditCard;
import com.smarthealthfinance.cards.domain.repository.CreditCardRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/** Idempotente. */
@Service
public class ReactivateCard {

    private static final Logger log = LoggerFactory.getLogger(ReactivateCard.class);

    private final CardAccess access;
    private final CreditCardRepository cards;
    private final Clock clock;
    private final CardEvents events;

    public ReactivateCard(CardAccess access, CreditCardRepository cards, Clock clock, CardEvents events) {
        this.access = access;
        this.cards = cards;
        this.clock = clock;
        this.events = events;
    }

    @Transactional
    public CardView execute(UUID workspaceId, UUID cardId) {
        CreditCard card = access.requireCard(workspaceId, cardId);

        boolean changed = card.reactivate(clock.instant());
        cards.save(card);
        if (changed) {
            events.reactivated(card);
        }

        log.info("Card reactivated cardId={} workspaceId={}", card.id(), card.workspaceId());

        return CardView.from(card);
    }
}
