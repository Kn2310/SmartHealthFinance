package com.smarthealthfinance.copilot.infrastructure.messaging;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.stereotype.Component;

/**
 * Última parada de um {@code consent.revoked} do Copilot: tentativas esgotadas ou falha não retentável. A mensagem vai
 * para a DLQ para análise e replay controlado (runbook do Copilot); até lá o histórico da pessoa segue guardado, e o
 * job de retenção continua valendo. Só o id e o tipo vão para o log. O contador
 * {@code shf.copilot.history.dead_lettered} alimenta o alerta P2 de privacidade (ADR-0042 §4).
 */
@Component
public class CopilotFailureRecoverer implements MessageRecoverer {

    private static final Logger log = LoggerFactory.getLogger(CopilotFailureRecoverer.class);

    private final Counter deadLettered;

    public CopilotFailureRecoverer(MeterRegistry meters) {
        this.deadLettered = Counter.builder("shf.copilot.history.dead_lettered")
                .description("consent.revoked do Copilot enviados à DLQ")
                .register(meters);
    }

    @Override
    public void recover(Message message, Throwable cause) {
        deadLettered.increment();
        log.error("Copilot event sent to DLQ messageId={} eventType={} error={}",
                message.getMessageProperties().getMessageId(), message.getMessageProperties().getType(),
                cause == null ? null : cause.getClass().getSimpleName());
        throw new AmqpRejectAndDontRequeueException("Copilot history revocation failed", cause);
    }
}
