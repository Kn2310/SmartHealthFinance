package com.smarthealthfinance.copilot.application.usecase;

import com.smarthealthfinance.consents.domain.enums.ConsentPurpose;
import com.smarthealthfinance.copilot.application.port.ConversationStore;
import com.smarthealthfinance.identity.domain.valueobject.UserId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.application.port.ProcessedEvents;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Consumidor {@code copilot.history-revocation} de {@code consent.revoked} (ADR-0038 §4, ADR-0040 §6): quando o
 * propósito é {@code COPILOT_HISTORY}, apaga as mensagens da pessoa no Workspace criadas até o {@code occurredAt} do
 * evento, e as conversas que ficarem vazias (uma entrega atrasada não apaga o que veio de um novo consentimento; a revogação de
 * {@code COPILOT_DATA_USE} já emite um revoked de HISTORY em cascata). Outros propósitos são ignorados. O apagamento e
 * a inbox ({@code ProcessedEvents}) commitam juntos; uma reentrega não tem efeito. Todo {@code consent.revoked} entra na inbox, inclusive os ignorados.
 */
@Service
public class DeleteConversationsOnRevocation {

    public static final String CONSUMER = "copilot.history-revocation";

    private static final Logger log = LoggerFactory.getLogger(DeleteConversationsOnRevocation.class);

    private final ProcessedEvents processedEvents;
    private final ConversationStore store;
    private final MeterRegistry meters;

    public DeleteConversationsOnRevocation(ProcessedEvents processedEvents, ConversationStore store,
                                           MeterRegistry meters) {
        this.processedEvents = processedEvents;
        this.store = store;
        this.meters = meters;
    }

    /**
     * @param purpose propósito do payload, como texto
     * @return verdadeiro se o histórico foi tratado agora; falso se o propósito é outro ou o evento já foi processado
     */
    @Transactional
    public boolean execute(UUID eventId, WorkspaceId workspaceId, UserId userId, String purpose, Instant occurredAt) {
        if (!processedEvents.markProcessed(CONSUMER, eventId)) {
            log.info("Copilot revocation already processed eventId={} workspaceId={}", eventId, workspaceId);
            return false;
        }
        if (!ConsentPurpose.COPILOT_HISTORY.name().equals(purpose)) {
            return false;
        }
        int deleted = store.deleteUpTo(workspaceId, userId, occurredAt);
        if (deleted > 0) {
            meters.counter("shf.copilot.history.deleted", "cause", "REVOCATION").increment(deleted);
        }
        log.info("Copilot history deleted on revocation workspaceId={} eventId={} count={}", workspaceId, eventId,
                deleted);
        return true;
    }
}
