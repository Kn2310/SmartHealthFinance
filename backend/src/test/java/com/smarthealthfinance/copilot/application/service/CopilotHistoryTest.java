package com.smarthealthfinance.copilot.application.service;

import com.smarthealthfinance.consents.application.port.ConsentReader;
import com.smarthealthfinance.consents.domain.enums.ConsentPurpose;
import com.smarthealthfinance.copilot.application.dto.CopilotResult;
import com.smarthealthfinance.copilot.application.dto.NewMessage;
import com.smarthealthfinance.copilot.application.dto.PreviousQuestion;
import com.smarthealthfinance.copilot.application.exception.ConversationNotFoundException;
import com.smarthealthfinance.copilot.application.port.ConversationStore;
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
import java.time.Duration;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Regras do histórico no envio (ADR-0040 §6, §7): consentimento, título, expiração de 90 dias e continuidade. */
class CopilotHistoryTest {

    private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");
    private static final CopilotAdmission.Member MEMBER = new CopilotAdmission.Member(
            new WorkspaceId(UUID.randomUUID()), new UserId(UUID.randomUUID()));

    private final ConsentReader consents = mock(ConsentReader.class);
    private final ConversationStore store = mock(ConversationStore.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final CopilotHistory history = new CopilotHistory(consents, store, Clock.fixed(NOW, ZoneOffset.UTC),
            meters);

    private static CopilotResult result() {
        return new CopilotResult(UUID.randomUUID(), ResultCode.OK, Intent.CARDS,
                RenderedAnswer.plain(AnswerType.ANALYSIS, "Resposta", List.of()), null, ValidationStatus.VALID, null,
                null, Map.of());
    }

    private void historyActive(boolean active) {
        when(consents.isActive(MEMBER.workspaceId(), MEMBER.userId(), ConsentPurpose.COPILOT_HISTORY))
                .thenReturn(active);
    }

    @Test
    void theFirstQuestionCreatesTheConversationWithTheTitleAndExpiresIn90Days() {
        historyActive(true);
        UUID conversation = UUID.randomUUID();
        when(store.createConversation(eq(MEMBER.workspaceId()), eq(MEMBER.userId()), any(), eq(NOW)))
                .thenReturn(conversation);
        when(store.addMessage(any(), any(), any(), any())).thenReturn(true);

        Optional<CopilotHistory.Recorded> recorded = history.record(MEMBER, null,
                "Por que minha fatura está mais alta?", result(), NOW);

        assertThat(recorded).get().satisfies(r -> assertThat(r.conversationId()).isEqualTo(conversation));
        verify(store).createConversation(MEMBER.workspaceId(), MEMBER.userId(), "Por que minha fatura está mais alta?",
                NOW);
        ArgumentCaptor<NewMessage> message = ArgumentCaptor.forClass(NewMessage.class);
        verify(store).addMessage(eq(MEMBER.workspaceId()), eq(MEMBER.userId()), eq(conversation), message.capture());
        assertThat(message.getValue().expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(90)));
        assertThat(message.getValue().createdAt()).isEqualTo(NOW);
        assertThat(meters.counter("shf.copilot.history.written", "kind", "CREATED").count()).isEqualTo(1);
    }

    @Test
    void anExistingConversationIsAppendedToWithoutANewTitle() {
        historyActive(true);
        UUID conversation = UUID.randomUUID();
        when(store.addMessage(any(), any(), eq(conversation), any())).thenReturn(true);

        history.record(MEMBER, conversation, "E em agosto?", result(), NOW);

        verify(store, never()).createConversation(any(), any(), any(), any());
        assertThat(meters.counter("shf.copilot.history.written", "kind", "APPENDED").count()).isEqualTo(1);
    }

    @Test
    void withoutHistoryConsentNothingIsWrittenAndAVanishedConversationKeepsTheAnswerEphemeral() {
        historyActive(false);
        assertThat(history.record(MEMBER, null, "Pergunta", result(), NOW)).isEmpty();
        verify(store, never()).createConversation(any(), any(), any(), any());
        verify(store, never()).addMessage(any(), any(), any(), any());

        historyActive(true);
        when(store.addMessage(any(), any(), any(), any())).thenReturn(false);
        assertThat(history.record(MEMBER, UUID.randomUUID(), "Pergunta", result(), NOW)).isEmpty();
    }

    @Test
    void continuingNeedsHistoryAndAVisibleConversationAndReadsTheLastThreeQuestions() {
        UUID conversation = UUID.randomUUID();
        historyActive(false);
        assertThatThrownBy(() -> history.continueFrom(MEMBER, conversation))
                .isInstanceOf(ConversationNotFoundException.class);

        historyActive(true);
        when(store.isVisible(MEMBER.workspaceId(), MEMBER.userId(), conversation, NOW)).thenReturn(false);
        assertThatThrownBy(() -> history.continueFrom(MEMBER, conversation))
                .isInstanceOf(ConversationNotFoundException.class);

        when(store.isVisible(MEMBER.workspaceId(), MEMBER.userId(), conversation, NOW)).thenReturn(true);
        when(store.latestQuestions(MEMBER.workspaceId(), MEMBER.userId(), conversation, NOW, 3))
                .thenReturn(List.of(new PreviousQuestion("a", Intent.CARDS), new PreviousQuestion("b", null)));
        assertThat(history.continueFrom(MEMBER, conversation))
                .containsExactly(new PreviousQuestion("a", Intent.CARDS), new PreviousQuestion("b", null));
    }

    @Test
    void replayIsEmptyWithoutHistory() {
        historyActive(false);

        assertThat(history.replay(MEMBER, UUID.randomUUID())).isEmpty();
        verify(store, never()).findByRequest(any(), any(), any(), any());
    }
}
