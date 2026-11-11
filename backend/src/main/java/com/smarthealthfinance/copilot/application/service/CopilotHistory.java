package com.smarthealthfinance.copilot.application.service;

import com.smarthealthfinance.consents.application.port.ConsentReader;
import com.smarthealthfinance.consents.domain.enums.ConsentPurpose;
import com.smarthealthfinance.copilot.application.dto.CopilotResult;
import com.smarthealthfinance.copilot.application.dto.NewMessage;
import com.smarthealthfinance.copilot.application.dto.StoredMessage;
import com.smarthealthfinance.copilot.application.exception.ConversationNotFoundException;
import com.smarthealthfinance.copilot.application.port.ConversationStore;
import com.smarthealthfinance.copilot.application.usecase.AskCopilot;
import com.smarthealthfinance.copilot.domain.service.ConversationTitle;
import com.smarthealthfinance.shared.domain.UuidV7;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Histórico de conversas no envio de perguntas (ADR-0040 §5–§7). Cada método é uma transação própria e curta, nunca
 * a do pipeline: a pergunta ao modelo roda fora de qualquer transação. O consentimento {@code COPILOT_HISTORY} é
 * conferido na mesma transação que lê ou grava (a janela entre a conferência e o commit fica coberta pelo job de
 * retenção: risco aceito, ADR-0040 §6).
 */
@Service
public class CopilotHistory {

    /** Cada mensagem expira 90 dias depois de criada (ADR-0040 §6). */
    public static final Duration RETENTION = Duration.ofDays(90);

    /** A conversa e a mensagem que acabaram de ser gravadas. */
    public record Recorded(UUID conversationId, UUID messageId) {
    }

    private final ConsentReader consents;
    private final ConversationStore store;
    private final Clock clock;
    private final MeterRegistry meters;

    public CopilotHistory(ConsentReader consents, ConversationStore store, Clock clock, MeterRegistry meters) {
        this.consents = consents;
        this.store = store;
        this.clock = clock;
        this.meters = meters;
    }

    /**
     * As até {@link AskCopilot#MAX_PREVIOUS_QUESTIONS} últimas perguntas da conversa (continuidade, ADR-0040 §7), da
     * mais antiga para a mais nova; nunca respostas.
     *
     * @throws ConversationNotFoundException sem {@code COPILOT_HISTORY}, conversa inexistente, de outra pessoa ou
     *         expirada
     */
    @Transactional(readOnly = true)
    public List<String> continueFrom(CopilotAdmission.Member member, UUID conversationId) {
        Instant now = clock.instant();
        if (!historyActive(member) || !store.isVisible(member.workspaceId(), member.userId(), conversationId, now)) {
            throw new ConversationNotFoundException();
        }
        return store.latestQuestions(member.workspaceId(), member.userId(), conversationId, now,
                AskCopilot.MAX_PREVIOUS_QUESTIONS);
    }

    /**
     * Grava a pergunta e a resposta (cria a conversa se {@code conversationId} é nulo). Vazio, sem gravar, quando o
     * histórico não está ativo ou a conversa sumiu entre a validação e a gravação: a resposta segue efêmera.
     */
    @Transactional
    public Optional<Recorded> record(CopilotAdmission.Member member, UUID conversationId, String question,
                                     CopilotResult result, Instant createdAt) {
        if (!historyActive(member)) {
            meters.counter("shf.copilot.history.skipped", "reason", "NO_CONSENT").increment();
            return Optional.empty();
        }
        UUID conversation = conversationId;
        boolean created = conversation == null;
        if (created) {
            conversation = store.createConversation(member.workspaceId(), member.userId(),
                    ConversationTitle.from(question), createdAt);
        }
        UUID messageId = UuidV7.generate(createdAt);
        NewMessage message = new NewMessage(messageId, question, result, createdAt, createdAt.plus(RETENTION));
        if (!store.addMessage(member.workspaceId(), member.userId(), conversation, message)) {
            meters.counter("shf.copilot.history.skipped", "reason", "CONVERSATION_GONE").increment();
            return Optional.empty();
        }
        meters.counter("shf.copilot.history.written", "kind", created ? "CREATED" : "APPENDED").increment();
        return Optional.of(new Recorded(conversation, messageId));
    }

    /** A mensagem guardada de um replay concluído (ADR-0040 §5); vazio sem histórico ou se já foi apagada. */
    @Transactional(readOnly = true)
    public Optional<StoredMessage> replay(CopilotAdmission.Member member, UUID requestId) {
        if (!historyActive(member)) {
            return Optional.empty();
        }
        return store.findByRequest(member.workspaceId(), member.userId(), requestId, clock.instant());
    }

    boolean historyActive(CopilotAdmission.Member member) {
        return consents.isActive(member.workspaceId(), member.userId(), ConsentPurpose.COPILOT_HISTORY);
    }
}
