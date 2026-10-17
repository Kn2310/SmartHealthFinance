package com.smarthealthfinance.insights.infrastructure.messaging;

import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.insights.application.usecase.MarkInsightsDirty;
import com.smarthealthfinance.shared.infrastructure.messaging.EventEnvelope;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumidor dos Insights (ADR-0022 §2): {@code health.snapshot-updated} só marca o Workspace para geração; o payload
 * não importa (a geração relê tudo). O ack só sai depois do commit.
 */
@Component
public class InsightsEventListener {

    private static final String CORRELATION_ID_MDC_KEY = "correlationId";

    private final MarkInsightsDirty mark;
    private final JsonMapper json;

    public InsightsEventListener(MarkInsightsDirty mark, JsonMapper json) {
        this.mark = mark;
        this.json = json;
    }

    @RabbitListener(queues = InsightsMessagingConfig.QUEUE,
            containerFactory = InsightsMessagingConfig.CONTAINER_FACTORY)
    public void onEvent(Message message) {
        EventEnvelope event = target(message, json);
        String previousCorrelation = MDC.get(CORRELATION_ID_MDC_KEY);
        if (event.correlationId() != null) {
            MDC.put(CORRELATION_ID_MDC_KEY, event.correlationId());
        }
        try {
            mark.execute(event.eventId(), new WorkspaceId(event.workspaceId()));
        }
        finally {
            if (previousCorrelation == null) {
                MDC.remove(CORRELATION_ID_MDC_KEY);
            }
            else {
                MDC.put(CORRELATION_ID_MDC_KEY, previousCorrelation);
            }
        }
    }

    /** @throws AmqpRejectAndDontRequeueException evento que nunca vai poder ser processado */
    static EventEnvelope target(Message message, JsonMapper json) {
        EventEnvelope event = EventEnvelope.from(message, json);
        if (event.workspaceId() == null || event.eventVersion() != 1
                || !InsightsMessagingConfig.SNAPSHOT_UPDATED.equals(event.eventType())) {
            throw new AmqpRejectAndDontRequeueException("Unexpected event on insights queue");
        }
        return event;
    }
}
