package com.smarthealthfinance.insights.infrastructure.messaging;

import org.junit.jupiter.api.Test;
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

/** Só {@code health.snapshot-updated} v1 com Workspace (ADR-0022 §2); o resto vai direto para a DLQ. */
class InsightsEventListenerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void acceptsTheHealthSnapshotEvent() {
        assertThat(InsightsEventListener.target(message("health.snapshot-updated", 1, true), JSON).eventType())
                .isEqualTo("health.snapshot-updated");
    }

    @ParameterizedTest
    @CsvSource({"analytics.projection-updated, 1, true", "health.snapshot-updated, 2, true",
            "health.snapshot-updated, 1, false"})
    void rejectsEverythingElse(String type, int version, boolean withWorkspace) {
        assertThatThrownBy(() -> InsightsEventListener.target(message(type, version, withWorkspace), JSON))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
    }

    @ParameterizedTest
    @CsvSource({"true", "false"})
    void retriesOnlyTransientFailures(boolean transientFailure) {
        Throwable failure = transientFailure
                ? new org.springframework.dao.QueryTimeoutException("timeout")
                : new IllegalStateException("bug");
        assertThat(InsightsMessagingConfig.isRetryable(failure)).isEqualTo(transientFailure);
    }

    private static Message message(String type, int version, boolean withWorkspace) {
        String body = """
                {"eventId": "%s", "eventType": "%s", "eventVersion": %d, "occurredAt": "2026-10-16T10:00:00Z",
                 "publishedAt": "2026-10-16T10:00:01Z", "workspaceId": %s, "aggregateType": "HealthSnapshot",
                 "aggregateId": "%s", "payload": {"algorithmVersion": "health-v1"}}
                """.formatted(UUID.randomUUID(), type, version,
                withWorkspace ? "\"" + UUID.randomUUID() + "\"" : "null", UUID.randomUUID());
        return new Message(body.getBytes(StandardCharsets.UTF_8), new MessageProperties());
    }
}
