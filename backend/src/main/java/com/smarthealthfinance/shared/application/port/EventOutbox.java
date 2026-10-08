package com.smarthealthfinance.shared.application.port;

import com.smarthealthfinance.shared.application.event.IntegrationEvent;

import java.util.UUID;

/**
 * Transactional Outbox (specs 05.4/05.7, ADR-0009 §14): o evento é gravado na mesma transação de banco do fato e
 * publicado depois, at-least-once. Exige transação ativa.
 */
public interface EventOutbox {

    /** @return o eventId atribuído */
    UUID append(IntegrationEvent event);
}
