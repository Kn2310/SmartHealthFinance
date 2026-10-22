package com.smarthealthfinance.cards.application.service;

import com.smarthealthfinance.cards.domain.model.CardInvoice;
import com.smarthealthfinance.cards.domain.model.CreditCard;
import com.smarthealthfinance.shared.application.event.IntegrationEvent;
import com.smarthealthfinance.shared.application.port.EventOutbox;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Eventos de Cartões v1 (ADR-0025 §11, ADR-0026 §8), gravados na outbox na mesma transação do fato e só quando algo
 * mudou. O payload carrega só ids e o mês da fatura; o consumidor relê a fonte. Nunca apelido, dígitos ou valores.
 */
@Service
public class CardEvents {

    public static final String CREATED = "card.created";
    public static final String UPDATED = "card.updated";
    public static final String ARCHIVED = "card.archived";
    public static final String REACTIVATED = "card.reactivated";
    public static final String INVOICE_CLOSED = "invoice.closed";

    public static final int VERSION = 1;

    private final EventOutbox outbox;

    public CardEvents(EventOutbox outbox) {
        this.outbox = outbox;
    }

    public void created(CreditCard card) {
        append(CREATED, card);
    }

    public void updated(CreditCard card) {
        append(UPDATED, card);
    }

    public void archived(CreditCard card) {
        append(ARCHIVED, card);
    }

    public void reactivated(CreditCard card) {
        append(REACTIVATED, card);
    }

    public void invoiceClosed(CardInvoice invoice) {
        outbox.append(new IntegrationEvent(INVOICE_CLOSED, VERSION, invoice.workspaceId().value(), "CardInvoice",
                invoice.id().value(), null, Map.of(
                        "cardId", invoice.cardId().value().toString(),
                        "invoiceId", invoice.id().value().toString(),
                        "dueMonth", invoice.dates().dueMonth().toString())));
    }

    private void append(String eventType, CreditCard card) {
        outbox.append(new IntegrationEvent(eventType, VERSION, card.workspaceId().value(), "CreditCard",
                card.id().value(), null, Map.of("cardId", card.id().value().toString())));
    }
}
