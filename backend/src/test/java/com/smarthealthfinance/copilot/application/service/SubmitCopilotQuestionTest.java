package com.smarthealthfinance.copilot.application.service;

import com.smarthealthfinance.copilot.application.dto.AskCommand;
import com.smarthealthfinance.copilot.application.dto.CopilotResult;
import com.smarthealthfinance.copilot.application.exception.ContextNotFoundException;
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
import com.smarthealthfinance.copilot.domain.enums.AnswerType;
import com.smarthealthfinance.copilot.domain.enums.Intent;
import com.smarthealthfinance.copilot.domain.enums.ResultCode;
import com.smarthealthfinance.copilot.domain.enums.ValidationStatus;
import com.smarthealthfinance.copilot.domain.model.EntryContext;
import com.smarthealthfinance.copilot.domain.model.RenderedAnswer;
import com.smarthealthfinance.identity.application.exception.WorkspaceNotFoundException;
import com.smarthealthfinance.identity.domain.valueobject.UserId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.InvalidValueException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Idempotência e ordem do envio de perguntas (ADR-0040 §4, §5) com a idempotência em memória: reserva atômica, replay
 * em andamento, corpo diferente, concluído, liberação nos resultados 403/429/503 e nas exceções, e a membership antes
 * de tocar o armazenamento.
 */
class SubmitCopilotQuestionTest {

    private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");
    private static final UUID WORKSPACE = UUID.fromString("7d1c0a52-0000-4000-8000-000000000001");
    private static final UserId USER = new UserId(UUID.fromString("7d1c0a52-0000-4000-8000-000000000002"));
    private static final String KEY = "web:7a4d1c52-aaaa-4bbb-8ccc-0123456789ab";

    private final CopilotAdmission admission = mock(CopilotAdmission.class);
    private final AskCopilot askCopilot = mock(AskCopilot.class);
    private final InMemoryIdempotency idempotency = new InMemoryIdempotency();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final SubmitCopilotQuestion service = new SubmitCopilotQuestion(admission, idempotency, askCopilot,
            Clock.fixed(NOW, ZoneOffset.UTC), meters);

    private static CopilotResult result(ResultCode code) {
        RenderedAnswer answer = RenderedAnswer.plain(AnswerType.ANALYSIS, "Resposta", List.of());
        boolean answered = code == ResultCode.OK || code == ResultCode.SAFE_MODE;
        return new CopilotResult(UUID.randomUUID(), code, answered ? Intent.CARDS : null, answered ? answer : null,
                null, ValidationStatus.VALID, code == ResultCode.RATE_LIMITED ? Duration.ofSeconds(12) : null,
                code == ResultCode.QUOTA_EXCEEDED ? Instant.parse("2026-12-01T03:00:00Z") : null, Map.of());
    }

    private void member() {
        when(admission.requireMember(WORKSPACE)).thenReturn(new CopilotAdmission.Member(new WorkspaceId(WORKSPACE),
                USER));
    }

    @Test
    void reservesTheKeyAsksAndMarksItDoneWithoutKeepingAnyContent() {
        member();
        when(askCopilot.ask(any(), any(AskCommand.class))).thenReturn(result(ResultCode.OK));

        SubmitCopilotQuestion.Outcome outcome = service.submit(WORKSPACE, KEY, null,
                AskCommand.of("  Por que a fatura subiu?  "));

        assertThat(outcome.question()).isEqualTo("Por que a fatura subiu?");
        assertThat(outcome.createdAt()).isEqualTo(NOW);
        assertThat(idempotency.states.values()).singleElement().satisfies(state -> assertThat(state.done).isTrue());
        assertThat(idempotency.states.values().toString()).doesNotContain("fatura");
        assertThat(meters.counter("shf.copilot.api.responses", "code", "OK").count()).isEqualTo(1);
    }

    @Test
    void replayOfAnInProgressKeyIsConflictAndNeverSpendsAnything() {
        member();
        AskCommand command = AskCommand.of("Pergunta");
        idempotency.reserve(new WorkspaceId(WORKSPACE), USER, KEY, SubmitCopilotQuestion.fingerprint(command, null),
                UUID.randomUUID());

        assertThatThrownBy(() -> service.submit(WORKSPACE, KEY, null, command))
                .isInstanceOf(CopilotRequestInProgressException.class);

        verify(askCopilot, never()).ask(any(), any(AskCommand.class));
        assertThat(meters.counter("shf.copilot.api.replays", "result", "IN_PROGRESS").count()).isEqualTo(1);
    }

    @Test
    void sameKeyWithAnotherBodyIsReusedEvenWhenTheFirstIsDone() {
        member();
        when(askCopilot.ask(any(), any(AskCommand.class))).thenReturn(result(ResultCode.OK));
        service.submit(WORKSPACE, KEY, null, AskCommand.of("Primeira"));

        assertThatThrownBy(() -> service.submit(WORKSPACE, KEY, null, AskCommand.of("Outra")))
                .isInstanceOf(CopilotKeyReusedException.class);
        assertThatThrownBy(() -> service.submit(WORKSPACE, KEY, null, new AskCommand("Primeira",
                List.of("anterior"), null))).isInstanceOf(CopilotKeyReusedException.class);
        assertThatThrownBy(() -> service.submit(WORKSPACE, KEY, null, new AskCommand("Primeira", List.of(),
                new EntryContext.Goal(UUID.randomUUID())))).isInstanceOf(CopilotKeyReusedException.class);

        assertThat(meters.counter("shf.copilot.api.replays", "result", "KEY_REUSED").count()).isEqualTo(3);
        verify(askCopilot, times(1)).ask(any(), any(AskCommand.class));
    }

    @Test
    void replayOfAnAnsweredQuestionWithoutHistoryIsAlreadyAnsweredAndIsNotChargedAgain() {
        member();
        when(askCopilot.ask(any(), any(AskCommand.class))).thenReturn(result(ResultCode.OK));
        service.submit(WORKSPACE, KEY, null, AskCommand.of("Pergunta"));

        assertThatThrownBy(() -> service.submit(WORKSPACE, KEY, null, AskCommand.of(" Pergunta ")))
                .isInstanceOf(CopilotAlreadyAnsweredException.class);

        verify(askCopilot, times(1)).ask(any(), any(AskCommand.class));
        assertThat(meters.counter("shf.copilot.api.replays", "result", "ALREADY_ANSWERED").count()).isEqualTo(1);
    }

    @Test
    void refusedResultsBecomeTheirErrorsAndReleaseTheKeySoTheSameKeyCanBeRetried() {
        member();
        when(askCopilot.ask(any(), any(AskCommand.class))).thenReturn(result(ResultCode.CONSENT_REQUIRED))
                .thenReturn(result(ResultCode.RATE_LIMITED)).thenReturn(result(ResultCode.QUOTA_EXCEEDED))
                .thenReturn(result(ResultCode.PROVIDER_UNAVAILABLE)).thenReturn(result(ResultCode.OK));
        AskCommand command = AskCommand.of("Pergunta");

        assertThatThrownBy(() -> service.submit(WORKSPACE, KEY, null, command))
                .isInstanceOf(CopilotConsentRequiredException.class);
        assertThat(idempotency.states).isEmpty();
        assertThatThrownBy(() -> service.submit(WORKSPACE, KEY, null, command))
                .isInstanceOfSatisfying(CopilotRateLimitedException.class,
                        e -> assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(12)));
        assertThatThrownBy(() -> service.submit(WORKSPACE, KEY, null, command))
                .isInstanceOfSatisfying(CopilotQuotaExceededException.class,
                        e -> assertThat(e.resetsAt()).isEqualTo(Instant.parse("2026-12-01T03:00:00Z")));
        assertThatThrownBy(() -> service.submit(WORKSPACE, KEY, null, command))
                .isInstanceOf(CopilotUnavailableException.class);
        assertThat(idempotency.states).isEmpty();

        assertThat(service.submit(WORKSPACE, KEY, null, command).result().code()).isEqualTo(ResultCode.OK);
    }

    @Test
    void anExceptionFromTheUseCaseReleasesTheKey() {
        member();
        when(askCopilot.ask(any(), any(AskCommand.class))).thenThrow(new ContextNotFoundException());

        assertThatThrownBy(() -> service.submit(WORKSPACE, KEY, null, AskCommand.of("Pergunta")))
                .isInstanceOf(ContextNotFoundException.class);

        assertThat(idempotency.states).isEmpty();
    }

    @Test
    void storageDownFailsClosedWithoutCallingTheUseCase() {
        member();
        idempotency.unavailable = true;

        assertThatThrownBy(() -> service.submit(WORKSPACE, KEY, null, AskCommand.of("Pergunta")))
                .isInstanceOf(CopilotUnavailableException.class);

        verify(askCopilot, never()).ask(any(), any(AskCommand.class));
    }

    @Test
    void aNonMemberIsNotFoundBeforeTheStorageIsTouched() {
        when(admission.requireMember(WORKSPACE)).thenThrow(new WorkspaceNotFoundException());

        assertThatThrownBy(() -> service.submit(WORKSPACE, KEY, null, AskCommand.of("Pergunta")))
                .isInstanceOf(WorkspaceNotFoundException.class);

        assertThat(idempotency.calls).isZero();
    }

    @Test
    void invalidKeysAndConversationShapesNeverTouchTheStorage() {
        member();
        for (String key : new String[] {null, " ", "x".repeat(101), "chave com espaço"}) {
            assertThatThrownBy(() -> service.submit(WORKSPACE, key, null, AskCommand.of("Pergunta")))
                    .isInstanceOf(InvalidValueException.class);
        }
        assertThatThrownBy(() -> service.submit(WORKSPACE, KEY, UUID.randomUUID(),
                new AskCommand("Pergunta", List.of("anterior"), null))).isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> service.submit(WORKSPACE, KEY, UUID.randomUUID(), AskCommand.of("Pergunta")))
                .as("sem COPILOT_HISTORY (Parte D2) não existe conversa guardada")
                .isInstanceOf(ConversationNotFoundException.class);

        assertThat(idempotency.calls).isZero();
        verify(askCopilot, never()).ask(any(), any(AskCommand.class));
    }

    @Test
    void theBodyHashSeparatesFieldsAndDependsOnEveryPart() {
        String base = SubmitCopilotQuestion.fingerprint(new AskCommand("ab", List.of("c"), null), null);

        assertThat(base).hasSize(64);
        assertThat(SubmitCopilotQuestion.fingerprint(new AskCommand("a", List.of("bc"), null), null))
                .isNotEqualTo(base);
        assertThat(SubmitCopilotQuestion.fingerprint(new AskCommand("ab", List.of(), null), null))
                .isNotEqualTo(base);
        assertThat(SubmitCopilotQuestion.fingerprint(new AskCommand("ab", List.of("c"),
                new EntryContext.Invoice(UUID.randomUUID(), YearMonth.of(2026, 10))), null)).isNotEqualTo(base);
        assertThat(SubmitCopilotQuestion.fingerprint(new AskCommand("ab", List.of("c"), null), UUID.randomUUID()))
                .isNotEqualTo(base);
        assertThat(SubmitCopilotQuestion.fingerprint(new AskCommand(" ab ", List.of(" c "), null), null))
                .as("o strip não muda o corpo").isEqualTo(base);
    }

    /** Mesma semântica do adapter Redis: reserva atômica por chave, só hash e estado. */
    private static final class InMemoryIdempotency implements CopilotIdempotency {

        record State(String bodyHash, UUID requestId, boolean done) {
        }

        final Map<String, State> states = new HashMap<>();
        boolean unavailable;
        int calls;

        private static String key(WorkspaceId workspace, UserId user, String key) {
            return workspace.value() + ":" + user.value() + ":" + key;
        }

        @Override
        public Reservation reserve(WorkspaceId workspaceId, UserId userId, String key, String bodyHash,
                                   UUID requestId) {
            calls++;
            if (unavailable) {
                return new Reservation.Unavailable();
            }
            String id = key(workspaceId, userId, key);
            State existing = states.get(id);
            if (existing == null) {
                states.put(id, new State(bodyHash, requestId, false));
                return new Reservation.Reserved();
            }
            if (!existing.bodyHash().equals(bodyHash)) {
                return new Reservation.Mismatch();
            }
            return existing.done() ? new Reservation.Done(existing.requestId())
                    : new Reservation.InProgress(existing.requestId());
        }

        @Override
        public void complete(WorkspaceId workspaceId, UserId userId, String key, String bodyHash, UUID requestId) {
            calls++;
            states.put(key(workspaceId, userId, key), new State(bodyHash, requestId, true));
        }

        @Override
        public void release(WorkspaceId workspaceId, UserId userId, String key) {
            calls++;
            states.remove(key(workspaceId, userId, key));
        }
    }
}
