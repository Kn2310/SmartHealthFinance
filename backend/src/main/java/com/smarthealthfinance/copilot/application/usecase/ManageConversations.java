package com.smarthealthfinance.copilot.application.usecase;

import com.smarthealthfinance.consents.application.port.ConsentReader;
import com.smarthealthfinance.consents.domain.enums.ConsentPurpose;
import com.smarthealthfinance.copilot.application.dto.ConversationCursor;
import com.smarthealthfinance.copilot.application.dto.ConversationDetail;
import com.smarthealthfinance.copilot.application.dto.ConversationPage;
import com.smarthealthfinance.copilot.application.dto.ConversationSummary;
import com.smarthealthfinance.copilot.application.exception.ConversationNotFoundException;
import com.smarthealthfinance.copilot.application.port.ConversationStore;
import com.smarthealthfinance.copilot.application.service.CopilotAdmission;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Leitura e exclusão do histórico de conversas (ADR-0040 §2, §6). Tudo da pessoa dentro do Workspace: conversa de outra
 * pessoa ou de outro Workspace é inexistente. Só a membership é exigida para apagar tudo; ler e apagar uma conversa
 * exigem {@code COPILOT_HISTORY} ativo, conferido na mesma transação. Exclusão é hard delete (05.12).
 */
@Service
public class ManageConversations {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 50;

    private static final Logger log = LoggerFactory.getLogger(ManageConversations.class);

    private final CopilotAdmission admission;
    private final ConsentReader consents;
    private final ConversationStore store;
    private final Clock clock;
    private final MeterRegistry meters;

    public ManageConversations(CopilotAdmission admission, ConsentReader consents, ConversationStore store,
                               Clock clock, MeterRegistry meters) {
        this.admission = admission;
        this.consents = consents;
        this.store = store;
        this.clock = clock;
        this.meters = meters;
    }

    /**
     * Página de conversas, da mais recente para a mais antiga. Sem {@code COPILOT_HISTORY}: {@code historyEnabled}
     * falso e lista vazia.
     *
     * @param limit padrão {@value #DEFAULT_LIMIT}; limitado a 1..{@value #MAX_LIMIT}
     * @param before cursor opaco da página anterior, ou nulo
     * @throws com.smarthealthfinance.shared.domain.InvalidValueException cursor inválido
     */
    @Transactional(readOnly = true)
    public ConversationPage list(UUID workspaceId, Integer limit, String before) {
        CopilotAdmission.Member member = admission.requireMember(workspaceId);
        ConversationCursor cursor = before == null || before.isBlank() ? null : ConversationCursor.parse(before);
        if (!historyActive(member)) {
            return new ConversationPage(false, List.of(), null);
        }
        int size = limit == null ? DEFAULT_LIMIT : Math.min(MAX_LIMIT, Math.max(1, limit));
        List<ConversationSummary> fetched = store.list(member.workspaceId(), member.userId(), clock.instant(),
                cursor, size + 1);
        boolean more = fetched.size() > size;
        List<ConversationSummary> items = more ? fetched.subList(0, size) : fetched;
        String next = more ? new ConversationCursor(items.getLast().lastMessageAt(), items.getLast().id()).encode()
                : null;
        return new ConversationPage(true, items, next);
    }

    /** @throws ConversationNotFoundException sem histórico, de outra pessoa ou Workspace, expirada ou inexistente */
    @Transactional(readOnly = true)
    public ConversationDetail get(UUID workspaceId, UUID conversationId) {
        CopilotAdmission.Member member = admission.requireMember(workspaceId);
        if (!historyActive(member)) {
            throw new ConversationNotFoundException();
        }
        return store.find(member.workspaceId(), member.userId(), conversationId, clock.instant())
                .orElseThrow(ConversationNotFoundException::new);
    }

    /** @throws ConversationNotFoundException sem histórico, de outra pessoa ou Workspace, expirada ou inexistente */
    @Transactional
    public void delete(UUID workspaceId, UUID conversationId) {
        CopilotAdmission.Member member = admission.requireMember(workspaceId);
        if (!historyActive(member)
                || !store.delete(member.workspaceId(), member.userId(), conversationId, clock.instant())) {
            throw new ConversationNotFoundException();
        }
        meters.counter("shf.copilot.history.deleted", "cause", "USER").increment();
        log.info("Copilot conversation deleted workspaceId={} count=1", member.workspaceId());
    }

    /** Apaga todas as conversas da pessoa no Workspace; idempotente (zero conversas também é 204). */
    @Transactional
    public void deleteAll(UUID workspaceId) {
        CopilotAdmission.Member member = admission.requireMember(workspaceId);
        int deleted = store.deleteAll(member.workspaceId(), member.userId());
        if (deleted > 0) {
            meters.counter("shf.copilot.history.deleted", "cause", "USER").increment(deleted);
            log.info("Copilot conversations deleted workspaceId={} count={}", member.workspaceId(), deleted);
        }
    }

    private boolean historyActive(CopilotAdmission.Member member) {
        return consents.isActive(member.workspaceId(), member.userId(), ConsentPurpose.COPILOT_HISTORY);
    }
}
