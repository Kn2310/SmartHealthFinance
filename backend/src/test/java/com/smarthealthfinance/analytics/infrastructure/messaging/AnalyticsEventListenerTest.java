package com.smarthealthfinance.analytics.infrastructure.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Do envelope aos meses que o consumidor recalcula (ADR-0014 §4). */
class AnalyticsEventListenerTest {

    private static final String WORKSPACE = "01922f5e-0000-7000-8000-000000000002";

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void transactionEventAffectsTheMonthOfItsBusinessDate() {
        var target = AnalyticsEventListener.target(message("transaction.posted", 1, WORKSPACE,
                "{\"transactionId\":\"01922f5e-0000-7000-8000-000000000003\",\"occurredOn\":\"2026-09-22\"}"), json);

        assertThat(target.workspaceId().value()).isEqualTo(UUID.fromString(WORKSPACE));
        assertThat(target.months()).containsExactly(YearMonth.of(2026, 9));
        assertThat(target.event().publishedAt()).isNotNull();
    }

    @Test
    void importCompletedAffectsEveryMonthOfTheFilePeriod() {
        var target = AnalyticsEventListener.target(message("import.completed", 1, WORKSPACE,
                "{\"importId\":\"x\",\"firstDate\":\"2026-07-01\",\"lastDate\":\"2026-09-28\"}"), json);

        assertThat(target.months()).containsExactly(YearMonth.of(2026, 7), YearMonth.of(2026, 8),
                YearMonth.of(2026, 9));
    }

    @Test
    void importWithoutDatesHasNothingToRecompute() {
        var target = AnalyticsEventListener.target(message("import.completed", 1, WORKSPACE,
                "{\"importId\":\"x\",\"importedLines\":0}"), json);

        assertThat(target.months()).isEmpty();
    }

    @Test
    void unprocessableMessagesGoStraightToTheDeadLetterQueue() {
        assertRejected(message("transaction.posted", 2, WORKSPACE, "{\"occurredOn\":\"2026-09-22\"}"));
        assertRejected(message("transaction.posted", 1, null, "{\"occurredOn\":\"2026-09-22\"}"));
        assertRejected(message("transaction.posted", 1, WORKSPACE, "{}"));
        assertRejected(message("transaction.posted", 1, WORKSPACE, "{\"occurredOn\":\"22/09/2026\"}"));
        assertRejected(message("account.created", 1, WORKSPACE, "{}"));
        assertRejected(message("import.completed", 1, WORKSPACE,
                "{\"firstDate\":\"2026-09-28\",\"lastDate\":\"2026-07-01\"}"));
        assertRejected(new Message("not json".getBytes(StandardCharsets.UTF_8), new MessageProperties()));
    }

    private void assertRejected(Message message) {
        assertThatThrownBy(() -> AnalyticsEventListener.target(message, json))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
    }

    private static Message message(String type, int version, String workspace, String payload) {
        String envelope = """
                {"eventId":"01922f5e-0000-7000-8000-000000000001","eventType":"%s","eventVersion":%d,
                 "occurredAt":"2026-09-27T12:00:00Z","publishedAt":"2026-09-27T12:00:01Z",
                 "workspaceId":%s,"aggregateType":"Transaction",
                 "aggregateId":"01922f5e-0000-7000-8000-000000000003","correlationId":null,"causationId":null,
                 "traceId":null,"payload":%s}
                """.formatted(type, version, workspace == null ? "null" : "\"" + workspace + "\"", payload);
        return new Message(envelope.getBytes(StandardCharsets.UTF_8), new MessageProperties());
    }
}
