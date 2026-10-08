package com.smarthealthfinance.shared.infrastructure.messaging;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/** Envelope de evento da spec 05.7, como trafega no RabbitMQ. */
public record EventEnvelope(UUID eventId, String eventType, int eventVersion, Instant occurredAt,
                            Instant publishedAt, UUID workspaceId, String aggregateType, UUID aggregateId,
                            String correlationId, UUID causationId, String traceId, JsonNode payload) {

    byte[] toJson(JsonMapper json) {
        ObjectNode node = json.createObjectNode();
        node.put("eventId", eventId.toString());
        node.put("eventType", eventType);
        node.put("eventVersion", eventVersion);
        node.put("occurredAt", occurredAt.toString());
        node.put("publishedAt", publishedAt.toString());
        node.put("workspaceId", workspaceId == null ? null : workspaceId.toString());
        node.put("aggregateType", aggregateType);
        node.put("aggregateId", aggregateId.toString());
        node.put("correlationId", correlationId);
        node.put("causationId", causationId == null ? null : causationId.toString());
        node.put("traceId", traceId);
        node.set("payload", payload);
        return json.writeValueAsBytes(node);
    }

    /**
     * Lê o envelope de uma mensagem recebida.
     *
     * @throws AmqpRejectAndDontRequeueException mensagem ilegível: vai direto para a DLQ, sem retry
     */
    public static EventEnvelope from(Message message, JsonMapper json) {
        try {
            JsonNode node = json.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
            return new EventEnvelope(
                    UUID.fromString(node.required("eventId").asString()),
                    node.required("eventType").asString(),
                    node.required("eventVersion").asInt(),
                    Instant.parse(node.required("occurredAt").asString()),
                    Instant.parse(node.required("publishedAt").asString()),
                    uuidOrNull(node.get("workspaceId")),
                    node.required("aggregateType").asString(),
                    UUID.fromString(node.required("aggregateId").asString()),
                    textOrNull(node.get("correlationId")),
                    uuidOrNull(node.get("causationId")),
                    textOrNull(node.get("traceId")),
                    node.required("payload"));
        }
        catch (JacksonException | IllegalArgumentException | java.time.format.DateTimeParseException invalid) {
            throw new AmqpRejectAndDontRequeueException("Malformed event envelope", invalid);
        }
    }

    /** Campo obrigatório do payload como UUID. */
    public UUID payloadUuid(String field) {
        JsonNode value = payload.get(field);
        if (value == null || !value.isString()) {
            throw new AmqpRejectAndDontRequeueException("Event payload missing " + field);
        }
        try {
            return UUID.fromString(value.asString());
        }
        catch (IllegalArgumentException invalid) {
            throw new AmqpRejectAndDontRequeueException("Event payload has invalid " + field, invalid);
        }
    }

    private static UUID uuidOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : UUID.fromString(node.asString());
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asString();
    }
}
