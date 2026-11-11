package com.smarthealthfinance.copilot.application.port;

import com.smarthealthfinance.copilot.application.dto.ConversationCursor;
import com.smarthealthfinance.copilot.application.dto.ConversationDetail;
import com.smarthealthfinance.copilot.application.dto.ConversationSummary;
import com.smarthealthfinance.copilot.application.dto.NewMessage;
import com.smarthealthfinance.copilot.application.dto.PreviousQuestion;
import com.smarthealthfinance.copilot.application.dto.StoredMessage;
import com.smarthealthfinance.identity.domain.valueobject.UserId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Histórico de conversas do Copilot (ADR-0040 §6): sempre da pessoa dentro do Workspace; o de outra pessoa ou de outro
 * Workspace é inexistente. Mensagens com {@code expires_at <= now} nunca são devolvidas, mesmo antes do job de
 * retenção. O chamador confere {@code COPILOT_HISTORY} na mesma transação.
 */
public interface ConversationStore {

    /** A conversa existe para a pessoa e tem alguma mensagem não expirada. */
    boolean isVisible(WorkspaceId workspaceId, UserId userId, UUID conversationId, Instant now);

    /** As {@code limit} últimas perguntas da conversa, da mais antiga para a mais nova (nunca respostas), cada uma com a intenção gravada (nula se não houve). */
    List<PreviousQuestion> latestQuestions(WorkspaceId workspaceId, UserId userId, UUID conversationId, Instant now, int limit);

    /** Cria a conversa (sem mensagem; o chamador acrescenta a primeira na mesma transação). */
    UUID createConversation(WorkspaceId workspaceId, UserId userId, String title, Instant now);

    /** @return falso se a conversa não existe mais para a pessoa */
    boolean addMessage(WorkspaceId workspaceId, UserId userId, UUID conversationId, NewMessage message);

    Optional<StoredMessage> findByRequest(WorkspaceId workspaceId, UserId userId, UUID requestId, Instant now);

    /** Da mais recente para a mais antiga, só com mensagens não expiradas; {@code before} exclusivo, pode ser nulo. */
    List<ConversationSummary> list(WorkspaceId workspaceId, UserId userId, Instant now, ConversationCursor before,
                                   int limit);

    Optional<ConversationDetail> find(WorkspaceId workspaceId, UserId userId, UUID conversationId, Instant now);

    /** Hard delete de uma conversa visível; falso se não existe para a pessoa. */
    boolean delete(WorkspaceId workspaceId, UserId userId, UUID conversationId, Instant now);

    /** Hard delete de todas as conversas da pessoa no Workspace; devolve quantas. */
    int deleteAll(WorkspaceId workspaceId, UserId userId);

    /** Retenção: apaga até {@code batch} mensagens expiradas (lock sem espera); atravessa Workspaces. */
    int purgeExpiredMessages(Instant now, int batch);

    /** Retenção: apaga até {@code batch} conversas sem mensagem. */
    int purgeEmptyConversations(int batch);
}
