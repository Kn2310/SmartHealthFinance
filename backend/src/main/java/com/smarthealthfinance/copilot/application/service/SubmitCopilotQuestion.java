package com.smarthealthfinance.copilot.application.service;

import com.smarthealthfinance.copilot.application.dto.AskCommand;
import com.smarthealthfinance.copilot.application.dto.CopilotResult;
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
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Envio de uma pergunta pela API (ADR-0040 §4, §5): autenticação → membership → {@code Idempotency-Key} → formato do
 * corpo → reserva da chave → {@link AskCopilot} (consentimento, contexto, rate limit, quota, validação da pergunta,
 * pipeline). Um replay nunca gasta token do balde nem quota. Resultados 403, 429 e 503, e qualquer exceção, liberam a
 * chave para a pessoa tentar de novo. Nunca roda numa transação de banco.
 *
 * <p>Sem {@code COPILOT_HISTORY} (a Parte D2 traz o histórico), um replay concluído é sempre
 * {@code COPILOT_ALREADY_ANSWERED}; com histórico, a D2 devolverá a mensagem guardada no lugar da exceção.
 */
@Service
public class SubmitCopilotQuestion {

    /** O resultado, a pergunta como foi enviada (normalizada) e o instante da resposta. */
    public record Outcome(CopilotResult result, String question, Instant createdAt) {

        /** Sem a pergunta nem a resposta. */
        @Override
        public String toString() {
            return "Outcome[" + result + "]";
        }
    }

    private final CopilotAdmission admission;
    private final CopilotIdempotency idempotency;
    private final AskCopilot askCopilot;
    private final Clock clock;
    private final MeterRegistry meters;

    public SubmitCopilotQuestion(CopilotAdmission admission, CopilotIdempotency idempotency, AskCopilot askCopilot,
                                 Clock clock, MeterRegistry meters) {
        this.admission = admission;
        this.idempotency = idempotency;
        this.askCopilot = askCopilot;
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
     * @throws ConversationNotFoundException {@code conversationId} informado (D1: ainda não há histórico)
     */
    public Outcome submit(UUID workspaceId, String idempotencyKey, UUID conversationId, AskCommand command) {
        CopilotAdmission.Member member = admission.requireMember(workspaceId);
        String key = new IdempotencyKey(idempotencyKey).value();
        if (conversationId != null) {
            if (!command.previousQuestions().isEmpty()) {
                throw new InvalidValueException("conversationId", "CONFLICTS_WITH_PREVIOUS_QUESTIONS");
            }
            // Conversa guardada só existe com COPILOT_HISTORY, que chega na Parte D2.
            throw new ConversationNotFoundException();
        }

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
            case CopilotIdempotency.Reservation.Done ignored -> {
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
            result = askCopilot.ask(workspaceId, command);
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
            default -> idempotency.complete(member.workspaceId(), member.userId(), key, bodyHash,
                    result.requestId());
        }
        return new Outcome(result, command.question().strip(), clock.instant());
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
