package com.smarthealthfinance.ingestion.infrastructure.messaging;

import com.smarthealthfinance.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Mensagens que nunca vão poder ser processadas vão direto para a DLQ, sem bloquear a fila (spec 05.7). */
class ImportWorkerIT extends IntegrationTest {

    @Autowired
    RabbitTemplate rabbit;

    @Autowired
    RabbitAdmin admin;

    @BeforeEach
    void purge() {
        admin.purgeQueue(ImportMessagingConfig.DEAD_LETTER_QUEUE, false);
    }

    @Test
    void malformedMessageGoesToTheDeadLetterQueue() {
        rabbit.send("", ImportMessagingConfig.QUEUE, new Message("not an envelope".getBytes(StandardCharsets.UTF_8),
                new MessageProperties()));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(deadLetters()).isEqualTo(1));
    }

    @Test
    void unknownImportIsAcknowledgedWithoutEffect() {
        String envelope = """
                {"eventId":"01922f5e-0000-7000-8000-000000000001","eventType":"import.confirmed","eventVersion":1,
                 "occurredAt":"2026-09-27T12:00:00Z","publishedAt":"2026-09-27T12:00:00Z",
                 "workspaceId":"01922f5e-0000-7000-8000-000000000002","aggregateType":"ImportBatch",
                 "aggregateId":"01922f5e-0000-7000-8000-000000000003","correlationId":null,"causationId":null,
                 "traceId":null,"payload":{"importId":"01922f5e-0000-7000-8000-000000000003"}}
                """;

        rabbit.send("", ImportMessagingConfig.QUEUE, new Message(envelope.getBytes(StandardCharsets.UTF_8),
                new MessageProperties()));

        await().pollDelay(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(admin.getQueueInfo(ImportMessagingConfig.QUEUE).getMessageCount()).isZero();
            assertThat(deadLetters()).isZero();
        });
    }

    private long deadLetters() {
        return admin.getQueueInfo(ImportMessagingConfig.DEAD_LETTER_QUEUE).getMessageCount();
    }
}
