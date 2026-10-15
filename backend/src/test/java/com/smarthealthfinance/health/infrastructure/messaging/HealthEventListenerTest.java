package com.smarthealthfinance.health.infrastructure.messaging;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Só os eventos do ADR-0018 §2, na versão 1 e com Workspace; o resto vai direto para a DLQ. */
class HealthEventListenerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @ParameterizedTest
    @CsvSource({"analytics.projection-updated", "account.changed"})
    void acceptsTheConsumedEvents(String type) {
        assertThat(HealthEventListener.target(message(type, 1, true), JSON).eventType()).isEqualTo(type);
    }

    @ParameterizedTest
    @CsvSource({"transaction.created, 1, true", "account.changed, 2, true", "account.changed, 1, false"})
    void rejectsEverythingElse(String type, int version, boolean withWorkspace) {
        assertThatThrownBy(() -> HealthEventListener.target(message(type, version, withWorkspace), JSON))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
    }

    @ParameterizedTest
    @CsvSource({"true", "false"})
    void retriesOnlyTransientFailures(boolean transientFailure) {
        Throwable failure = transientFailure
                ? new org.springframework.dao.QueryTimeoutException("timeout")
                : new IllegalStateException("bug");
        assertThat(HealthMessagingConfig.isRetryable(failure)).isEqualTo(transientFailure);
    }

    private static Message message(String type, int version, boolean withWorkspace) {
        String body = """
                {"eventId": "%s", "eventType": "%s", "eventVersion": %d, "occurredAt": "2026-10-15T10:00:00Z",
                 "publishedAt": "2026-10-15T10:00:01Z", "workspaceId": %s, "aggregateType": "Account",
                 "aggregateId": "%s", "payload": {}}
                """.formatted(UUID.randomUUID(), type, version,
                withWorkspace ? "\"" + UUID.randomUUID() + "\"" : "null", UUID.randomUUID());
        return new Message(body.getBytes(StandardCharsets.UTF_8), new MessageProperties());
    }
}
