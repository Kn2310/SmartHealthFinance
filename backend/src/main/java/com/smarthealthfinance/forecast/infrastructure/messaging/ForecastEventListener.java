package com.smarthealthfinance.forecast.infrastructure.messaging;

import com.smarthealthfinance.forecast.application.usecase.MarkForecastDirty;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.infrastructure.messaging.EventEnvelope;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumidor do Forecast (ADR-0031 §2, padrão do ADR-0018 §2): {@code analytics.projection-updated},
 * {@code account.changed} e os eventos de cartão e fatura só marcam o
 * Workspace para recálculo; o payload não importa (o recálculo relê tudo). O ack só sai depois do commit.
 */
@Component
public class ForecastEventListener {

    private static final String CORRELATION_ID_MDC_KEY = "correlationId";
    private static final Set<String> CONSUMED = Stream.concat(
            Stream.of(ForecastMessagingConfig.PROJECTION_UPDATED, ForecastMessagingConfig.ACCOUNT_CHANGED),
            ForecastMessagingConfig.CARD_EVENTS.stream()).collect(Collectors.toUnmodifiableSet());

    private final MarkForecastDirty mark;
    private final JsonMapper json;

    public ForecastEventListener(MarkForecastDirty mark, JsonMapper json) {
        this.mark = mark;
        this.json = json;
    }

    @RabbitListener(queues = ForecastMessagingConfig.QUEUE, containerFactory = ForecastMessagingConfig.CONTAINER_FACTORY)
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
        if (event.workspaceId() == null || event.eventVersion() != 1 || !CONSUMED.contains(event.eventType())) {
            throw new AmqpRejectAndDontRequeueException("Unexpected event on forecast queue");
        }
        return event;
    }
}
