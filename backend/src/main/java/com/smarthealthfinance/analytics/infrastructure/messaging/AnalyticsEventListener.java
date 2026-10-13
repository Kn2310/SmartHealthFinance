package com.smarthealthfinance.analytics.infrastructure.messaging;

import com.smarthealthfinance.analytics.application.usecase.RefreshAnalyticsProjection;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.infrastructure.messaging.EventEnvelope;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.TreeSet;

/**
 * Consumidor da projeção de Analytics (ADR-0014 §4). Traduz o envelope nos meses afetados e delega ao caso de uso;
 * o ack só sai depois do commit (container em modo AUTO).
 * <ul>
 *   <li>{@code transaction.*}: o mês de {@code occurredOn};</li>
 *   <li>{@code import.completed}: os meses entre {@code firstDate} e {@code lastDate} do arquivo (sem datas, nada
 *   a recalcular).</li>
 * </ul>
 */
@Component
public class AnalyticsEventListener {

    private static final String CORRELATION_ID_MDC_KEY = "correlationId";
    private static final String TRANSACTION_EVENT_PREFIX = "transaction.";

    private final RefreshAnalyticsProjection refresh;
    private final JsonMapper json;

    public AnalyticsEventListener(RefreshAnalyticsProjection refresh, JsonMapper json) {
        this.refresh = refresh;
        this.json = json;
    }

    @RabbitListener(queues = AnalyticsMessagingConfig.QUEUE,
            containerFactory = AnalyticsMessagingConfig.CONTAINER_FACTORY)
    public void onEvent(Message message) {
        Target target = target(message, json);
        String previousCorrelation = MDC.get(CORRELATION_ID_MDC_KEY);
        if (target.event().correlationId() != null) {
            MDC.put(CORRELATION_ID_MDC_KEY, target.event().correlationId());
        }
        try {
            refresh.execute(target.event().eventId(), target.event().publishedAt(), target.workspaceId(),
                    target.months());
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

    record Target(EventEnvelope event, WorkspaceId workspaceId, Set<YearMonth> months) {
    }

    /** @throws AmqpRejectAndDontRequeueException evento que nunca vai poder ser processado */
    static Target target(Message message, JsonMapper json) {
        EventEnvelope event = EventEnvelope.from(message, json);
        if (event.workspaceId() == null || event.eventVersion() != 1) {
            throw new AmqpRejectAndDontRequeueException("Unexpected event on analytics queue");
        }
        WorkspaceId workspace = new WorkspaceId(event.workspaceId());

        if (event.eventType().startsWith(TRANSACTION_EVENT_PREFIX)) {
            return new Target(event, workspace, Set.of(YearMonth.from(date(event.payload(), "occurredOn"))));
        }
        if (AnalyticsMessagingConfig.IMPORT_COMPLETED.equals(event.eventType())) {
            return new Target(event, workspace, importMonths(event.payload()));
        }
        throw new AmqpRejectAndDontRequeueException("Unexpected event on analytics queue");
    }

    private static Set<YearMonth> importMonths(JsonNode payload) {
        if (payload.get("firstDate") == null || payload.get("lastDate") == null) {
            return Set.of();
        }
        YearMonth first = YearMonth.from(date(payload, "firstDate"));
        YearMonth last = YearMonth.from(date(payload, "lastDate"));
        if (last.isBefore(first)) {
            throw new AmqpRejectAndDontRequeueException("Import period ends before it starts");
        }
        Set<YearMonth> months = new TreeSet<>();
        for (YearMonth month = first; !month.isAfter(last); month = month.plusMonths(1)) {
            months.add(month);
        }
        return months;
    }

    private static LocalDate date(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || !value.isString()) {
            throw new AmqpRejectAndDontRequeueException("Event payload missing " + field);
        }
        try {
            return LocalDate.parse(value.asString());
        }
        catch (DateTimeParseException invalid) {
            throw new AmqpRejectAndDontRequeueException("Event payload has invalid " + field, invalid);
        }
    }
}
