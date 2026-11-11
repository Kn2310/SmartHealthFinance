package com.smarthealthfinance.copilot.infrastructure.messaging;

import com.smarthealthfinance.copilot.application.usecase.DeleteConversationsOnRevocation;
import com.smarthealthfinance.identity.domain.valueobject.UserId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.infrastructure.messaging.EventEnvelope;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumidor do Copilot (ADR-0040 §6): {@code consent.revoked} v1 com {@code purpose = COPILOT_HISTORY} apaga as
 * conversas da pessoa do payload no Workspace do envelope; outros propósitos são ignorados. O ack só sai depois do
 * commit. O payload só tem ids e códigos (ADR-0038 §8).
 */
@Component
public class CopilotEventListener {

    private static final String CORRELATION_ID_MDC_KEY = "correlationId";

    private final DeleteConversationsOnRevocation deleteOnRevocation;
    private final JsonMapper json;

    public CopilotEventListener(DeleteConversationsOnRevocation deleteOnRevocation, JsonMapper json) {
        this.deleteOnRevocation = deleteOnRevocation;
        this.json = json;
    }

    @RabbitListener(queues = CopilotMessagingConfig.QUEUE,
            containerFactory = CopilotMessagingConfig.CONTAINER_FACTORY)
    public void onEvent(Message message) {
        EventEnvelope event = target(message, json);
        String previousCorrelation = MDC.get(CORRELATION_ID_MDC_KEY);
        if (event.correlationId() != null) {
            MDC.put(CORRELATION_ID_MDC_KEY, event.correlationId());
        }
        try {
            JsonNode purpose = event.payload().get("purpose");
            if (purpose == null || !purpose.isString()) {
                throw new AmqpRejectAndDontRequeueException("Event payload missing purpose");
            }
            deleteOnRevocation.execute(event.eventId(), new WorkspaceId(event.workspaceId()),
                    new UserId(event.payloadUuid("userId")), purpose.asString());
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
                || !CopilotMessagingConfig.CONSENT_REVOKED.equals(event.eventType())) {
            throw new AmqpRejectAndDontRequeueException("Unexpected event on copilot queue");
        }
        return event;
    }
}
