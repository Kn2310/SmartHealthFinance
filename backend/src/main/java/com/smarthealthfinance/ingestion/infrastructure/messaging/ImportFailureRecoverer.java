package com.smarthealthfinance.ingestion.infrastructure.messaging;

import com.smarthealthfinance.ingestion.application.usecase.FailImport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Última parada de uma mensagem de importação: tentativas esgotadas ou falha não retentável. Marca o batch como
 * FAILED (o usuário não fica vendo "processando" para sempre) e rejeita a mensagem para a DLQ, onde fica para
 * análise e replay controlado (spec 05.7).
 */
@Component
public class ImportFailureRecoverer implements MessageRecoverer {

    private static final Logger log = LoggerFactory.getLogger(ImportFailureRecoverer.class);

    private final FailImport failImport;
    private final JsonMapper json;

    public ImportFailureRecoverer(FailImport failImport, JsonMapper json) {
        this.failImport = failImport;
        this.json = json;
    }

    @Override
    public void recover(Message message, Throwable cause) {
        try {
            ImportConfirmedListener.Target target = ImportConfirmedListener.target(message, json);
            failImport.execute(target.workspaceId(), target.importId(), FailImport.PROCESSING_ERROR);
        }
        catch (AmqpRejectAndDontRequeueException unreadable) {
            log.error("Unreadable import message sent to DLQ messageId={}",
                    message.getMessageProperties().getMessageId());
        }
        catch (RuntimeException failure) {
            // A mensagem vai para a DLQ de qualquer forma; o batch fica em PROCESSING até o replay.
            log.error("Could not mark import as failed messageId={} error={}",
                    message.getMessageProperties().getMessageId(), failure.getClass().getSimpleName());
        }
        throw new AmqpRejectAndDontRequeueException("Import processing failed", cause);
    }
}
