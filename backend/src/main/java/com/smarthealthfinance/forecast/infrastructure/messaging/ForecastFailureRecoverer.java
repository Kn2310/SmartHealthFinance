package com.smarthealthfinance.forecast.infrastructure.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.stereotype.Component;

/**
 * Última parada de um evento do Forecast: tentativas esgotadas ou falha não retentável. A mensagem vai para a DLQ
 * para análise e replay controlado (spec 05.7); enquanto isso, o status do Forecast mostra {@code RECALCULATING}.
 * Só o id e o tipo vão para o log.
 */
@Component
public class ForecastFailureRecoverer implements MessageRecoverer {

    private static final Logger log = LoggerFactory.getLogger(ForecastFailureRecoverer.class);

    @Override
    public void recover(Message message, Throwable cause) {
        log.error("Forecast event sent to DLQ messageId={} eventType={} error={}",
                message.getMessageProperties().getMessageId(), message.getMessageProperties().getType(),
                cause == null ? null : cause.getClass().getSimpleName());
        throw new AmqpRejectAndDontRequeueException("Forecast snapshot marking failed", cause);
    }
}
