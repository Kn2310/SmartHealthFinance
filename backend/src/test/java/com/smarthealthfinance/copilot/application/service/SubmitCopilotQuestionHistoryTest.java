package com.smarthealthfinance.copilot.application.service;

import com.smarthealthfinance.copilot.application.dto.AskCommand;
import com.smarthealthfinance.copilot.application.dto.CopilotResult;
import com.smarthealthfinance.copilot.application.dto.PreviousQuestion;
import com.smarthealthfinance.copilot.application.exception.ConversationNotFoundException;
import com.smarthealthfinance.copilot.application.dto.StoredMessage;
import com.smarthealthfinance.copilot.application.exception.CopilotAlreadyAnsweredException;
import com.smarthealthfinance.copilot.application.exception.CopilotQuotaExceededException;
import com.smarthealthfinance.copilot.application.usecase.AskCopilot;
import com.smarthealthfinance.copilot.domain.enums.AnswerType;
import com.smarthealthfinance.copilot.domain.enums.Intent;
import com.smarthealthfinance.copilot.domain.enums.ResultCode;
import com.smarthealthfinance.copilot.domain.enums.ValidationStatus;
import com.smarthealthfinance.copilot.domain.model.RenderedAnswer;
import com.smarthealthfinance.identity.domain.valueobject.UserId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Continuidade, gravação e replay com histórico no envio de perguntas (ADR-0040 §5–§7), com o histórico e o pipeline
 * simulados e a idempotência em memória.
 */
class SubmitCopilotQuestionHistoryTest {

    private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");
    private static final UUID WORKSPACE = UUID.fromString("7d1c0a52-0000-4000-8000-000000000001");
    private static final UserId USER = new UserId(UUID.fromString("7d1c0a52-0000-4000-8000-000000000002"));
    private static final String KEY = "web:7a4d1c52-aaaa-4bbb-8ccc-0123456789ab";
    private static final CopilotAdmission.Member MEMBER = new CopilotAdmission.Member(new WorkspaceId(WORKSPACE),
            USER);

    private final CopilotAdmission admission = mock(CopilotAdmission.class);
    private final AskCopilot askCopilot = mock(AskCopilot.class);
    private final CopilotHistory history = mock(CopilotHistory.class);
    private final SubmitCopilotQuestionTest.InMemoryIdempotency idempotency =
            new SubmitCopilotQuestionTest.InMemoryIdempotency();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final SubmitCopilotQuestion service = new SubmitCopilotQuestion(admission, idempotency, askCopilot,
            history, Clock.fixed(NOW, ZoneOffset.UTC), meters);

    private static CopilotResult result(ResultCode code) {
        boolean answered = code == ResultCode.OK || code == ResultCode.SAFE_MODE || code == ResultCode.OUT_OF_SCOPE
                || code == ResultCode.INSUFFICIENT_DATA;
        RenderedAnswer answer = RenderedAnswer.plain(AnswerType.ANALYSIS, "Resposta", List.of());
        return new CopilotResult(UUID.randomUUID(), code, answered ? Intent.CARDS : null, answered ? answer : null,
                null, ValidationStatus.VALID, null, null, Map.of());
    }

    private void member() {
        when(admission.requireMember(WORKSPACE)).thenReturn(MEMBER);
    }

    @Test
    void aConversationFeedsTheLastQuestionsToThePipelineAndTheHashIgnoresThem() {
        member();
        UUID conversation = UUID.randomUUID();
        when(history.continueFrom(MEMBER, conversation)).thenReturn(List.of(new PreviousQuestion("Quanto gastei?", Intent.CARDS),
                new PreviousQuestion("E em agosto?", null)));
        when(askCopilot.ask(any(), any(AskCommand.class))).thenReturn(result(ResultCode.OK));

        service.submit(WORKSPACE, KEY, conversation, AskCommand.of("E em setembro?"));

        ArgumentCaptor<AskCommand> sent = ArgumentCaptor.forClass(AskCommand.class);
        verify(askCopilot).ask(eq(WORKSPACE), sent.capture());
        assertThat(sent.getValue().previousQuestions()).containsExactly(
                new PreviousQuestion("Quanto gastei?", Intent.CARDS), new PreviousQuestion("E em agosto?", null));
        assertThat(idempotency.states.values()).singleElement().satisfies(state -> assertThat(state.bodyHash())
                .isEqualTo(SubmitCopilotQuestion.fingerprint(AskCommand.of("E em setembro?"), conversation)));
    }

    @Test
    void anAnsweredQuestionIsRecordedAndTheOutcomeCarriesTheIds() {
        member();
        UUID conversation = UUID.randomUUID();
        UUID message = UUID.randomUUID();
        CopilotResult answered = result(ResultCode.SAFE_MODE);
        when(askCopilot.ask(any(), any(AskCommand.class))).thenReturn(answered);
        when(history.record(MEMBER, null, "Qual é o saldo?", answered, NOW))
                .thenReturn(Optional.of(new CopilotHistory.Recorded(conversation, message)));

        SubmitCopilotQuestion.Outcome outcome = service.submit(WORKSPACE, KEY, null,
                AskCommand.of(" Qual é o saldo? "));

        assertThat(outcome.conversationId()).isEqualTo(conversation);
        assertThat(outcome.messageId()).isEqualTo(message);
        assertThat(outcome.createdAt()).isEqualTo(NOW);
        assertThat(idempotency.states.values()).singleElement()
                .satisfies(state -> assertThat(state.messageId()).isEqualTo(message));
    }

    @Test
    void withoutHistoryTheOutcomeIsEphemeralAndAFailureWritingKeepsTheAnswer() {
        member();
        when(askCopilot.ask(any(), any(AskCommand.class))).thenReturn(result(ResultCode.OK));
        when(history.record(any(), any(), any(), any(), any())).thenReturn(Optional.empty())
                .thenThrow(new IllegalStateException("db"));

        SubmitCopilotQuestion.Outcome ephemeral = service.submit(WORKSPACE, KEY, null, AskCommand.of("Um"));
        SubmitCopilotQuestion.Outcome failed = service.submit(WORKSPACE, KEY + "2", null, AskCommand.of("Dois"));

        assertThat(ephemeral.conversationId()).isNull();
        assertThat(ephemeral.messageId()).isNull();
        assertThat(failed.result().code()).isEqualTo(ResultCode.OK);
        assertThat(failed.conversationId()).isNull();
        assertThat(meters.counter("shf.copilot.history.record_failed").count()).isEqualTo(1);
        assertThat(idempotency.states.values()).allSatisfy(state -> assertThat(state.done()).isTrue());
    }

    @Test
    void refusedResultsAreNeverRecorded() {
        member();
        when(askCopilot.ask(any(), any(AskCommand.class))).thenReturn(result(ResultCode.QUOTA_EXCEEDED));

        assertThatThrownBy(() -> service.submit(WORKSPACE, KEY, null, AskCommand.of("Pergunta")))
                .isInstanceOf(CopilotQuotaExceededException.class);

        verify(history, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void aReplayOfARecordedAnswerReturnsTheStoredMessageWithoutCallingThePipeline() {
        member();
        UUID conversation = UUID.randomUUID();
        UUID message = UUID.randomUUID();
        CopilotResult answered = result(ResultCode.OK);
        when(askCopilot.ask(any(), any(AskCommand.class))).thenReturn(answered);
        when(history.record(any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(new CopilotHistory.Recorded(conversation, message)));
        service.submit(WORKSPACE, KEY, null, AskCommand.of("Pergunta"));
        Instant stored = NOW.minusSeconds(5);
        when(history.replay(MEMBER, answered.requestId())).thenReturn(Optional.of(
                new StoredMessage(message, conversation, "Pergunta", stored, answered)));

        SubmitCopilotQuestion.Outcome replay = service.submit(WORKSPACE, KEY, null, AskCommand.of("Pergunta"));

        assertThat(replay.messageId()).isEqualTo(message);
        assertThat(replay.conversationId()).isEqualTo(conversation);
        assertThat(replay.createdAt()).isEqualTo(stored);
        assertThat(replay.result()).isSameAs(answered);
        verify(askCopilot, times(1)).ask(any(), any(AskCommand.class));
        assertThat(meters.counter("shf.copilot.api.replays", "result", "REPLAYED").count()).isEqualTo(1);
    }

    @Test
    void aReplayWhoseMessageIsGoneOrWasNeverStoredIsAlreadyAnswered() {
        member();
        UUID message = UUID.randomUUID();
        CopilotResult answered = result(ResultCode.OK);
        when(askCopilot.ask(any(), any(AskCommand.class))).thenReturn(answered);
        when(history.record(any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(new CopilotHistory.Recorded(UUID.randomUUID(), message)));
        service.submit(WORKSPACE, KEY, null, AskCommand.of("Pergunta"));
        when(history.replay(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submit(WORKSPACE, KEY, null, AskCommand.of("Pergunta")))
                .isInstanceOf(CopilotAlreadyAnsweredException.class);
        verify(history).replay(MEMBER, answered.requestId());
    }

    @Test
    void aReplayOfADoneKeyAnswersEvenIfTheConversationIsGoneAndNeverReadsIt() {
        member();
        UUID conversation = UUID.randomUUID();
        UUID message = UUID.randomUUID();
        CopilotResult answered = result(ResultCode.OK);
        when(history.continueFrom(MEMBER, conversation)).thenReturn(List.of());
        when(askCopilot.ask(any(), any(AskCommand.class))).thenReturn(answered);
        when(history.record(any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(new CopilotHistory.Recorded(conversation, message)));
        service.submit(WORKSPACE, KEY, conversation, AskCommand.of("Pergunta"));
        when(history.continueFrom(MEMBER, conversation)).thenThrow(new ConversationNotFoundException());
        when(history.replay(MEMBER, answered.requestId())).thenReturn(Optional.of(
                new StoredMessage(message, conversation, "Pergunta", NOW, answered)));

        SubmitCopilotQuestion.Outcome replay = service.submit(WORKSPACE, KEY, conversation,
                AskCommand.of("Pergunta"));

        assertThat(replay.messageId()).isEqualTo(message);
        verify(history, times(1)).continueFrom(MEMBER, conversation);
        verify(askCopilot, times(1)).ask(any(), any(AskCommand.class));
    }

    @Test
    void aReplayOfADoneKeyWithoutTheStoredMessageIsAlreadyAnsweredEvenIfTheConversationIsGone() {
        member();
        UUID conversation = UUID.randomUUID();
        CopilotResult answered = result(ResultCode.OK);
        when(history.continueFrom(MEMBER, conversation)).thenReturn(List.of());
        when(askCopilot.ask(any(), any(AskCommand.class))).thenReturn(answered);
        when(history.record(any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(new CopilotHistory.Recorded(conversation, UUID.randomUUID())));
        service.submit(WORKSPACE, KEY, conversation, AskCommand.of("Pergunta"));
        when(history.continueFrom(MEMBER, conversation)).thenThrow(new ConversationNotFoundException());
        when(history.replay(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submit(WORKSPACE, KEY, conversation, AskCommand.of("Pergunta")))
                .isInstanceOf(CopilotAlreadyAnsweredException.class);
    }

    @Test
    void aMissingConversationIsNotFoundAfterTheReservationAndReleasesTheKey() {
        member();
        UUID conversation = UUID.randomUUID();
        when(history.continueFrom(MEMBER, conversation)).thenThrow(new ConversationNotFoundException());

        assertThatThrownBy(() -> service.submit(WORKSPACE, KEY, conversation, AskCommand.of("Pergunta")))
                .isInstanceOf(ConversationNotFoundException.class);

        assertThat(idempotency.calls).as("reserva e liberação: a chave foi reservada antes de ler a conversa").isEqualTo(2);
        assertThat(idempotency.states).as("e liberada no 404").isEmpty();
        verify(askCopilot, never()).ask(any(), any(AskCommand.class));
    }
}
