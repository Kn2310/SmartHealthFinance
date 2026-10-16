package com.smarthealthfinance.insights.infrastructure.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.stereotype.Component;

/**
 * Última parada de um evento dos Insights: tentativas esgotadas ou falha não retentável. A mensagem vai para a DLQ
 * para análise e replay controlado (spec 05.7); enquanto isso, o status mostra {@code UPDATING}. Só o id e o tipo vão
 * para o log.
 */
@Component
public class InsightsFailureRecoverer implements MessageRecoverer {

    private static final Logger log = LoggerFactory.getLogger(InsightsFailureRecoverer.class);

    @Override
    public void recover(Message message, Throwable cause) {
        log.error("Insights event sent to DLQ messageId={} eventType={} error={}",
                message.getMessageProperties().getMessageId(), message.getMessageProperties().getType(),
                cause == null ? null : cause.getClass().getSimpleName());
        throw new AmqpRejectAndDontRequeueException("Insights generation marking failed", cause);
    }
}
