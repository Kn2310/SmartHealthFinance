package com.smarthealthfinance.shared.infrastructure.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventEnvelopeTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void roundTripsTheSpecEnvelope() {
        UUID importId = UUID.randomUUID();
        EventEnvelope original = new EventEnvelope(UUID.randomUUID(), "import.confirmed", 1,
                Instant.parse("2026-09-27T12:00:00Z"), Instant.parse("2026-09-27T12:00:01Z"), UUID.randomUUID(),
                "ImportBatch", importId, "corr-1", null, null,
                json.readTree("{\"importId\":\"" + importId + "\"}"));

        EventEnvelope read = EventEnvelope.from(new Message(original.toJson(json), new MessageProperties()), json);

        assertThat(read).isEqualTo(original);
        assertThat(read.payloadUuid("importId")).isEqualTo(importId);
    }

    @Test
    void unreadableMessagesGoStraightToTheDeadLetterQueue() {
        assertThatThrownBy(() -> EventEnvelope.from(message("not json"), json))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        assertThatThrownBy(() -> EventEnvelope.from(message("{\"eventType\":\"import.confirmed\"}"), json))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
    }

    private static Message message(String body) {
        return new Message(body.getBytes(StandardCharsets.UTF_8), new MessageProperties());
    }
}
