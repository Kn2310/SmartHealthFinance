package com.smarthealthfinance.shared.application.event;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Fato ocorrido a publicar via Transactional Outbox (spec 05.7). O envelope completo (eventId, occurredAt,
 * correlationId, traceId...) é montado pela infraestrutura.
 * <p>
 * O payload carrega só identificadores, códigos e contagens: nunca valores, descrições ou dados pessoais.
 *
 * @param eventType   {@code resource.action}, ex.: {@code import.confirmed}
 * @param workspaceId Workspace do fato, ou {@code null} para eventos de sistema
 * @param causationId evento que causou este, se houver
 */
public record IntegrationEvent(String eventType, int eventVersion, UUID workspaceId, String aggregateType,
                               UUID aggregateId, UUID causationId, Map<String, Object> payload) {

    private static final Pattern EVENT_TYPE = Pattern.compile("[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)+");

    public IntegrationEvent {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(aggregateType, "aggregateType");
        Objects.requireNonNull(aggregateId, "aggregateId");
        if (!EVENT_TYPE.matcher(eventType).matches()) {
            throw new IllegalArgumentException("eventType deve seguir resource.action: " + eventType);
        }
        if (eventVersion < 1) {
            throw new IllegalArgumentException("eventVersion deve ser positiva");
        }
        payload = Map.copyOf(payload);
    }
}
