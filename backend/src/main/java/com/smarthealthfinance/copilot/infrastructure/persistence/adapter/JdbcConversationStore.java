package com.smarthealthfinance.copilot.infrastructure.persistence.adapter;

import com.smarthealthfinance.copilot.application.dto.ConversationCursor;
import com.smarthealthfinance.copilot.application.dto.ConversationDetail;
import com.smarthealthfinance.copilot.application.dto.ConversationSummary;
import com.smarthealthfinance.copilot.application.dto.CopilotResult;
import com.smarthealthfinance.copilot.application.dto.NewMessage;
import com.smarthealthfinance.copilot.application.dto.PreviousQuestion;
import com.smarthealthfinance.copilot.application.dto.StoredMessage;
import com.smarthealthfinance.copilot.application.port.ConversationStore;
import com.smarthealthfinance.copilot.domain.enums.AnswerConfidence;
import com.smarthealthfinance.copilot.domain.enums.Intent;
import com.smarthealthfinance.copilot.domain.enums.ResultCode;
import com.smarthealthfinance.copilot.domain.enums.ValidationStatus;
import com.smarthealthfinance.copilot.domain.service.ConversationTitle;
import com.smarthealthfinance.identity.domain.valueobject.UserId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.UuidV7;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Histórico do Copilot em {@code copilot_conversations} e {@code copilot_messages} (ADR-0040 §6, §10). Toda consulta
 * filtra por Workspace e pessoa; mensagens expiradas ({@code expires_at <= now}) nunca saem, mesmo antes do job. Só
 * este pacote toca o banco do Copilot (ArchUnit). Nada daqui vai para log.
 */
@Repository
class JdbcConversationStore implements ConversationStore {

    private static final TypeReference<Map<String, String>> VERSIONS = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final StoredAnswerCodec codec;
    private final JsonMapper json;

    JdbcConversationStore(JdbcTemplate jdbc, StoredAnswerCodec codec, JsonMapper json) {
        this.jdbc = jdbc;
        this.codec = codec;
        this.json = json;
    }

    @Override
    public boolean isVisible(WorkspaceId workspaceId, UserId userId, UUID conversationId, Instant now) {
        Boolean visible = jdbc.queryForObject("""
                select exists (
                  select 1 from copilot_conversations c
                  where c.id = ? and c.workspace_id = ? and c.user_id = ?
                    and exists (select 1 from copilot_messages m where m.conversation_id = c.id and m.expires_at > ?))
                """, Boolean.class, conversationId, workspaceId.value(), userId.value(), Timestamp.from(now));
        return Boolean.TRUE.equals(visible);
    }

    @Override
    public List<PreviousQuestion> latestQuestions(WorkspaceId workspaceId, UserId userId, UUID conversationId,
                                                    Instant now, int limit) {
        List<PreviousQuestion> newestFirst = jdbc.query("""
                select m.question, m.intent from copilot_messages m
                where m.conversation_id = ? and m.workspace_id = ? and m.user_id = ? and m.expires_at > ?
                order by m.created_at desc, m.id desc
                limit ?
                """, (rs, row) -> new PreviousQuestion(rs.getString("question"), rs.getString("intent") == null ? null
                : Intent.valueOf(rs.getString("intent"))), conversationId, workspaceId.value(), userId.value(),
                Timestamp.from(now), limit);
        List<PreviousQuestion> questions = new ArrayList<>(newestFirst);
        Collections.reverse(questions);
        return questions;
    }

    @Override
    public UUID createConversation(WorkspaceId workspaceId, UserId userId, String title, Instant now) {
        UUID id = UuidV7.generate(now);
        jdbc.update("""
                insert into copilot_conversations (id, workspace_id, user_id, title, created_at, last_message_at)
                values (?, ?, ?, ?, ?, ?)
                """, id, workspaceId.value(), userId.value(), title, Timestamp.from(now), Timestamp.from(now));
        return id;
    }

    @Override
    public boolean addMessage(WorkspaceId workspaceId, UserId userId, UUID conversationId, NewMessage message) {
        // Trava a linha da conversa: a retenção (que apaga com lock sem espera) e a gravação não se cruzam.
        int touched = jdbc.update("""
                update copilot_conversations set last_message_at = greatest(last_message_at, ?)
                where id = ? and workspace_id = ? and user_id = ?
                """, Timestamp.from(message.createdAt()), conversationId, workspaceId.value(), userId.value());
        if (touched == 0) {
            return false;
        }
        CopilotResult result = message.result();
        jdbc.update("""
                insert into copilot_messages (id, conversation_id, workspace_id, user_id, request_id, question, intent,
                                              code, answer, confidence, validation, versions, created_at, expires_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?::jsonb, ?, ?)
                """, message.id(), conversationId, workspaceId.value(), userId.value(), result.requestId(),
                message.question(), result.intent() == null ? null : result.intent().name(), result.code().name(),
                codec.write(result.answerValue().orElseThrow(), result.notices()),
                result.confidence() == null ? null : result.confidence().name(), result.validation().name(),
                json.writeValueAsString(result.versions()), Timestamp.from(message.createdAt()),
                Timestamp.from(message.expiresAt()));
        return true;
    }

    @Override
    public Optional<StoredMessage> findByRequest(WorkspaceId workspaceId, UserId userId, UUID requestId, Instant now) {
        return jdbc.query(MESSAGE_SELECT + """
                where m.request_id = ? and m.workspace_id = ? and m.user_id = ? and m.expires_at > ?
                """, messageMapper(), requestId, workspaceId.value(), userId.value(), Timestamp.from(now))
                .stream().findFirst();
    }

    @Override
    public List<ConversationSummary> list(WorkspaceId workspaceId, UserId userId, Instant now,
                                          ConversationCursor before, int limit) {
        StringBuilder sql = new StringBuilder("""
                select c.id, c.title, c.created_at, c.last_message_at, count(m.id) as message_count
                from copilot_conversations c
                join copilot_messages m on m.conversation_id = c.id and m.expires_at > ?
                where c.workspace_id = ? and c.user_id = ?
                """);
        List<Object> args = new ArrayList<>(List.of(Timestamp.from(now), workspaceId.value(), userId.value()));
        if (before != null) {
            sql.append(" and (c.last_message_at, c.id) < (?, ?)");
            args.add(Timestamp.from(before.lastMessageAt()));
            args.add(before.id());
        }
        sql.append("""

                group by c.id
                order by c.last_message_at desc, c.id desc
                limit ?
                """);
        args.add(limit);
        return jdbc.query(sql.toString(), (rs, row) -> new ConversationSummary(rs.getObject("id", UUID.class),
                rs.getString("title"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("last_message_at").toInstant(), rs.getInt("message_count")), args.toArray());
    }

    @Override
    public Optional<ConversationDetail> find(WorkspaceId workspaceId, UserId userId, UUID conversationId,
                                             Instant now) {
        List<StoredMessage> messages = jdbc.query(MESSAGE_SELECT + """
                where m.conversation_id = ? and m.workspace_id = ? and m.user_id = ? and m.expires_at > ?
                order by m.created_at, m.id
                """, messageMapper(), conversationId, workspaceId.value(), userId.value(), Timestamp.from(now));
        if (messages.isEmpty()) {
            return Optional.empty();
        }
        return jdbc.query("""
                select id, title, created_at from copilot_conversations
                where id = ? and workspace_id = ? and user_id = ?
                """, (rs, row) -> new ConversationDetail(rs.getObject("id", UUID.class), rs.getString("title"),
                rs.getTimestamp("created_at").toInstant(), messages), conversationId, workspaceId.value(),
                userId.value()).stream().findFirst();
    }

    @Override
    public boolean delete(WorkspaceId workspaceId, UserId userId, UUID conversationId, Instant now) {
        return jdbc.update("""
                delete from copilot_conversations c
                where c.id = ? and c.workspace_id = ? and c.user_id = ?
                  and exists (select 1 from copilot_messages m where m.conversation_id = c.id and m.expires_at > ?)
                """, conversationId, workspaceId.value(), userId.value(), Timestamp.from(now)) > 0;
    }

    @Override
    public int deleteAll(WorkspaceId workspaceId, UserId userId) {
        return jdbc.update("delete from copilot_conversations where workspace_id = ? and user_id = ?",
                workspaceId.value(), userId.value());
    }

    @Override
    public int deleteUpTo(WorkspaceId workspaceId, UserId userId, Instant upTo) {
        List<UUID> touched = jdbc.queryForList("""
                delete from copilot_messages where workspace_id = ? and user_id = ? and created_at <= ?
                returning conversation_id
                """, UUID.class, workspaceId.value(), userId.value(), Timestamp.from(upTo)).stream().distinct()
                .toList();
        // O título nasce da primeira pergunta: quem sobrevive ganha o título da primeira pergunta que restou.
        for (UUID conversationId : touched) {
            jdbc.query("""
                    select question from copilot_messages where conversation_id = ?
                    order by created_at, id limit 1
                    """, (rs, row) -> rs.getString("question"), conversationId).stream().findFirst()
                    .ifPresent(first -> jdbc.update("""
                            update copilot_conversations set title = ? where id = ? and workspace_id = ? and user_id = ?
                            """, ConversationTitle.from(first), conversationId, workspaceId.value(), userId.value()));
        }
        return jdbc.update("""
                delete from copilot_conversations c
                where c.workspace_id = ? and c.user_id = ? and c.created_at <= ?
                  and not exists (select 1 from copilot_messages m where m.conversation_id = c.id)
                """, workspaceId.value(), userId.value(), Timestamp.from(upTo));
    }

    @Override
    public int purgeExpiredMessages(Instant now, int batch) {
        return jdbc.update("""
                delete from copilot_messages where id in (
                  select id from copilot_messages where expires_at <= ?
                  order by expires_at limit ? for update skip locked)
                """, Timestamp.from(now), batch);
    }

    @Override
    public int purgeEmptyConversations(int batch) {
        return jdbc.update("""
                delete from copilot_conversations where id in (
                  select c.id from copilot_conversations c
                  where not exists (select 1 from copilot_messages m where m.conversation_id = c.id)
                  limit ? for update skip locked)
                """, batch);
    }

    private static final String MESSAGE_SELECT = """
            select m.id, m.conversation_id, m.request_id, m.question, m.intent, m.code, m.answer, m.confidence,
                   m.validation, m.versions, m.created_at
            from copilot_messages m
            """;

    private RowMapper<StoredMessage> messageMapper() {
        return (ResultSet rs, int row) -> message(rs);
    }

    private StoredMessage message(ResultSet rs) throws SQLException {
        StoredAnswerCodec.Stored stored = codec.read(rs.getString("answer"));
        String intent = rs.getString("intent");
        String confidence = rs.getString("confidence");
        Instant createdAt = rs.getTimestamp("created_at").toInstant();
        CopilotResult result = new CopilotResult(rs.getObject("request_id", UUID.class),
                ResultCode.valueOf(rs.getString("code")), intent == null ? null : Intent.valueOf(intent),
                stored.answer(), confidence == null ? null : AnswerConfidence.valueOf(confidence),
                ValidationStatus.valueOf(rs.getString("validation")), null, null,
                json.readValue(rs.getString("versions"), VERSIONS), stored.notices());
        return new StoredMessage(rs.getObject("id", UUID.class), rs.getObject("conversation_id", UUID.class),
                rs.getString("question"), createdAt, result);
    }
}
