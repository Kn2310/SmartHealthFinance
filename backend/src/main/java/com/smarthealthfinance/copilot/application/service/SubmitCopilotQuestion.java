package com.smarthealthfinance.copilot.application.service;

import com.smarthealthfinance.copilot.application.dto.AskCommand;
import com.smarthealthfinance.copilot.application.dto.CopilotResult;
import com.smarthealthfinance.copilot.application.dto.StoredMessage;
import com.smarthealthfinance.copilot.application.exception.ConversationNotFoundException;
import com.smarthealthfinance.copilot.application.exception.CopilotAlreadyAnsweredException;
import com.smarthealthfinance.copilot.application.exception.CopilotConsentRequiredException;
import com.smarthealthfinance.copilot.application.exception.CopilotKeyReusedException;
import com.smarthealthfinance.copilot.application.exception.CopilotQuotaExceededException;
import com.smarthealthfinance.copilot.application.exception.CopilotRateLimitedException;
import com.smarthealthfinance.copilot.application.exception.CopilotRequestInProgressException;
import com.smarthealthfinance.copilot.application.exception.CopilotUnavailableException;
import com.smarthealthfinance.copilot.application.port.CopilotIdempotency;
import com.smarthealthfinance.copilot.application.usecase.AskCopilot;
import com.smarthealthfinance.copilot.domain.model.EntryContext;
import com.smarthealthfinance.shared.domain.InvalidValueException;
import com.smarthealthfinance.transactions.domain.valueobject.IdempotencyKey;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Envio de uma pergunta pela API (ADR-0040 §4, §5): autenticação → membership → {@code Idempotency-Key} → formato do
 * corpo → reserva da chave → {@link AskCopilot} (consentimento, contexto, rate limit, quota, validação da pergunta,
 * pipeline). Um replay nunca gasta token do balde nem quota. Resultados 403, 429 e 503, e qualquer exceção, liberam a
 * chave para a pessoa tentar de novo. Nunca roda numa transação de banco.
 *
 * <p>Histórico (ADR-0040 §5–§7): com {@code conversationId}, a conversa é validada antes de tudo (404) e o servidor lê
 * as até 3 últimas perguntas dela como continuidade; depois da resposta (fora de qualquer transação), a pergunta e a
 * resposta são gravadas com {@code COPILOT_HISTORY} ativo, conferido na transação da gravação. Um replay concluído
 * devolve a mensagem guardada; sem mensagem, é {@code COPILOT_ALREADY_ANSWERED}.
 */
@Service
public class SubmitCopilotQuestion {

    private static final Logger log = LoggerFactory.getLogger(SubmitCopilotQuestion.class);

    /**
     * O resultado, a pergunta como foi enviada (normalizada), o instante da resposta e, com histórico, a conversa e a
     * mensagem guardadas (nulos na conversa efêmera).
     */
    public record Outcome(CopilotResult result, String question, Instant createdAt, UUID conversationId,
                          UUID messageId) {

        /** Conversa efêmera. */
        public Outcome(CopilotResult result, String question, Instant createdAt) {
            this(result, question, createdAt, null, null);
        }

        /** Sem a pergunta nem a resposta. */
        @Override
        public String toString() {
            return "Outcome[" + result + "]";
        }
    }

    private final CopilotAdmission admission;
    private final CopilotIdempotency idempotency;
    private final AskCopilot askCopilot;
    private final CopilotHistory history;
    private final Clock clock;
    private final MeterRegistry meters;

    public SubmitCopilotQuestion(CopilotAdmission admission, CopilotIdempotency idempotency, AskCopilot askCopilot,
                                 CopilotHistory history, Clock clock, MeterRegistry meters) {
        this.admission = admission;
        this.idempotency = idempotency;
        this.askCopilot = askCopilot;
        this.history = history;
        this.clock = clock;
        this.meters = meters;
    }

    /**
     * @param idempotencyKey valor do header, obrigatório
     * @param conversationId conversa guardada (só com {@code COPILOT_HISTORY}); nula na conversa efêmera
     * @throws com.smarthealthfinance.identity.application.exception.WorkspaceNotFoundException não membro, sem tocar
     *         o Redis
     * @throws InvalidValueException chave ausente ou inválida; {@code conversationId} junto de
     *         {@code previousQuestions}; pergunta inválida
     * @throws ConversationNotFoundException {@code conversationId} sem {@code COPILOT_HISTORY}, inexistente, de outra
     *         pessoa ou Workspace, ou expirada; antes de tocar o Redis e o pipeline
     */
    public Outcome submit(UUID workspaceId, String idempotencyKey, UUID conversationId, AskCommand command) {
        CopilotAdmission.Member member = admission.requireMember(workspaceId);
        String key = new IdempotencyKey(idempotencyKey).value();
        List<String> continuity = List.of();
        if (conversationId != null) {
            if (!command.previousQuestions().isEmpty()) {
                throw new InvalidValueException("conversationId", "CONFLICTS_WITH_PREVIOUS_QUESTIONS");
            }
            continuity = history.continueFrom(member, conversationId);
        }

        // O hash é do corpo como o cliente mandou; a continuidade lida no servidor não entra.
        String bodyHash = fingerprint(command, conversationId);
        CopilotIdempotency.Reservation reservation = idempotency.reserve(member.workspaceId(), member.userId(), key,
                bodyHash, UUID.randomUUID());
        switch (reservation) {
            case CopilotIdempotency.Reservation.Reserved ignored -> {
                // segue para a pergunta
            }
            case CopilotIdempotency.Reservation.InProgress ignored -> {
                replay("IN_PROGRESS");
                throw new CopilotRequestInProgressException();
            }
            case CopilotIdempotency.Reservation.Done done -> {
                Optional<StoredMessage> stored = done.messageId() == null ? Optional.empty()
                        : history.replay(member, done.requestId());
                if (stored.isPresent()) {
                    replay("REPLAYED");
                    StoredMessage message = stored.get();
                    return new Outcome(message.result(), message.question(), message.createdAt(),
                            message.conversationId(), message.id());
                }
                replay("ALREADY_ANSWERED");
                throw new CopilotAlreadyAnsweredException();
            }
            case CopilotIdempotency.Reservation.Mismatch ignored -> {
                replay("KEY_REUSED");
                throw new CopilotKeyReusedException();
            }
            case CopilotIdempotency.Reservation.Unavailable ignored -> {
                meters.counter("shf.copilot.api.idempotency_unavailable").increment();
                throw new CopilotUnavailableException();
            }
        }

        CopilotResult result;
        try {
            result = askCopilot.ask(workspaceId, continuity.isEmpty() ? command
                    : new AskCommand(command.question(), continuity, command.context()));
        } catch (RuntimeException e) {
            idempotency.release(member.workspaceId(), member.userId(), key);
            throw e;
        }
        meters.counter("shf.copilot.api.responses", "code", result.code().name()).increment();
        switch (result.code()) {
            case CONSENT_REQUIRED -> throw released(member, key, new CopilotConsentRequiredException());
            case RATE_LIMITED -> throw released(member, key, new CopilotRateLimitedException(result.retryAfter()));
            case QUOTA_EXCEEDED -> throw released(member, key, new CopilotQuotaExceededException(result.resetsAt()));
            case PROVIDER_UNAVAILABLE -> throw released(member, key, new CopilotUnavailableException());
            default -> {
                // segue: OK, SAFE_MODE, INSUFFICIENT_DATA e OUT_OF_SCOPE são gravados e concluem a chave
            }
        }
        String question = command.question().strip();
        Instant createdAt = clock.instant();
        Optional<CopilotHistory.Recorded> recorded = record(member, conversationId, question, result, createdAt);
        idempotency.complete(member.workspaceId(), member.userId(), key, bodyHash, result.requestId(),
                recorded.map(CopilotHistory.Recorded::messageId).orElse(null));
        return new Outcome(result, question, createdAt, recorded.map(CopilotHistory.Recorded::conversationId)
                .orElse(null), recorded.map(CopilotHistory.Recorded::messageId).orElse(null));
    }

    /**
     * Grava fora da transação do pipeline. Falha ao gravar não perde a resposta (já foi paga): ela segue efêmera, sem
     * {@code conversationId}, e a falha vira métrica e log só com o tipo do erro.
     */
    private Optional<CopilotHistory.Recorded> record(CopilotAdmission.Member member, UUID conversationId,
                                                     String question, CopilotResult result, Instant createdAt) {
        try {
            return history.record(member, conversationId, question, result, createdAt);
        } catch (RuntimeException e) {
            meters.counter("shf.copilot.history.record_failed").increment();
            log.warn("Copilot history write failed workspaceId={} requestId={} error={}", member.workspaceId(),
                    result.requestId(), e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private RuntimeException released(CopilotAdmission.Member member, String key, RuntimeException failure) {
        idempotency.release(member.workspaceId(), member.userId(), key);
        return failure;
    }

    private void replay(String result) {
        meters.counter("shf.copilot.api.replays", "result", result).increment();
    }

    /**
     * Hash do corpo (pergunta, anteriores, contexto, conversa) com cada campo prefixado pelo tamanho, para que campos
     * diferentes nunca produzam o mesmo digest. Só o digest vai ao Redis.
     */
    public static String fingerprint(AskCommand command, UUID conversationId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, command.question() == null ? "" : command.question().strip());
            update(digest, Integer.toString(command.previousQuestions().size()));
            command.previousQuestions().forEach(previous -> update(digest, previous.strip()));
            update(digest, context(command.context()));
            update(digest, conversationId == null ? "" : conversationId.toString());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String context(EntryContext context) {
        return switch (context) {
            case null -> "";
            case EntryContext.Insight insight -> "INSIGHT:" + insight.insightId();
            case EntryContext.Invoice invoice -> "INVOICE:" + invoice.cardId() + ":" + invoice.month();
            case EntryContext.InstallmentPlan plan -> "INSTALLMENT_PLAN:" + plan.cardId() + ":" + plan.planId();
            case EntryContext.Goal goal -> "GOAL:" + goal.goalId();
        };
    }

    private static void update(MessageDigest digest, String field) {
        byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) ':');
        digest.update(bytes);
    }
}
