package com.smarthealthfinance.shared.application.port;

import java.util.UUID;

/**
 * Inbox de consumidores críticos (spec 05.7): garante que uma entrega repetida do mesmo evento não tem efeito.
 * Exige transação ativa: a marca commita junto com o efeito do consumidor.
 */
public interface ProcessedEvents {

    /**
     * @return true na primeira vez; false se o consumidor já processou o evento (inclusive uma entrega concorrente
     *         que acabou de commitar)
     */
    boolean markProcessed(String consumer, UUID eventId);
}
