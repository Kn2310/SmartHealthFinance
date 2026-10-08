package com.smarthealthfinance.ingestion.infrastructure.messaging;

import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.ingestion.application.usecase.ConfirmImport;
import com.smarthealthfinance.ingestion.application.usecase.ProcessImport;
import com.smarthealthfinance.ingestion.domain.valueobject.ImportBatchId;
import com.smarthealthfinance.shared.infrastructure.messaging.EventEnvelope;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Import Worker (ADR-0009 §15): processa cada {@code import.confirmed}. O ack só sai depois do commit
 * (container em modo AUTO); a idempotência fica no caso de uso (inbox + máquina de estados).
 */
@Component
public class ImportConfirmedListener {

    private static final String CORRELATION_ID_MDC_KEY = "correlationId";

    private final ProcessImport processImport;
    private final JsonMapper json;

    public ImportConfirmedListener(ProcessImport processImport, JsonMapper json) {
        this.processImport = processImport;
        this.json = json;
    }

    @RabbitListener(queues = ImportMessagingConfig.QUEUE, containerFactory = ImportMessagingConfig.CONTAINER_FACTORY)
    public void onImportConfirmed(Message message) {
        Target target = target(message, json);
        String previousCorrelation = MDC.get(CORRELATION_ID_MDC_KEY);
        if (target.event().correlationId() != null) {
            MDC.put(CORRELATION_ID_MDC_KEY, target.event().correlationId());
        }
        try {
            processImport.execute(target.event().eventId(), target.workspaceId(), target.importId());
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

    record Target(EventEnvelope event, WorkspaceId workspaceId, ImportBatchId importId) {
    }

    /** @throws AmqpRejectAndDontRequeueException mensagem que nunca vai poder ser processada */
    static Target target(Message message, JsonMapper json) {
        EventEnvelope event = EventEnvelope.from(message, json);
        if (!ConfirmImport.EVENT_TYPE.equals(event.eventType()) || event.eventVersion() != 1
                || event.workspaceId() == null) {
            throw new AmqpRejectAndDontRequeueException("Unexpected event on import queue");
        }
        return new Target(event, new WorkspaceId(event.workspaceId()),
                new ImportBatchId(event.payloadUuid("importId")));
    }
}
