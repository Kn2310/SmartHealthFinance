package com.smarthealthfinance.copilot.application.usecase;

import com.smarthealthfinance.copilot.application.dto.CopilotResult;
import com.smarthealthfinance.copilot.application.dto.StoredMessage;
import com.smarthealthfinance.copilot.application.exception.CopilotRequestNotFoundException;
import com.smarthealthfinance.copilot.application.port.ConversationStore;
import com.smarthealthfinance.copilot.application.port.FeedbackStore;
import com.smarthealthfinance.copilot.application.service.CopilotAdmission;
import com.smarthealthfinance.copilot.domain.enums.AnswerType;
import com.smarthealthfinance.copilot.domain.enums.FeedbackRating;
import com.smarthealthfinance.copilot.domain.enums.FeedbackReason;
import com.smarthealthfinance.copilot.domain.enums.Intent;
import com.smarthealthfinance.copilot.domain.enums.ResultCode;
import com.smarthealthfinance.copilot.domain.enums.ValidationStatus;
import com.smarthealthfinance.copilot.domain.model.RenderedAnswer;
import com.smarthealthfinance.copilot.domain.repository.AiUsageLedger;
import com.smarthealthfinance.identity.domain.valueobject.UserId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.InvalidValueException;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Feedback por pergunta (ADR-0040 §8): só da pessoa no Workspace, último envio vale, sem conteúdo. */
class SubmitFeedbackTest {

    private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");
    private static final UUID WORKSPACE = UUID.randomUUID();
    private static final CopilotAdmission.Member MEMBER = new CopilotAdmission.Member(new WorkspaceId(WORKSPACE),
            new UserId(UUID.randomUUID()));

    private final CopilotAdmission admission = mock(CopilotAdmission.class);
    private final AiUsageLedger ledger = mock(AiUsageLedger.class);
    private final ConversationStore conversations = mock(ConversationStore.class);
    private final FeedbackStore feedback = mock(FeedbackStore.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final SubmitFeedback submit = new SubmitFeedback(admission, ledger, conversations, feedback,
            Clock.fixed(NOW, ZoneOffset.UTC), meters);
    private final UUID request = UUID.randomUUID();

    SubmitFeedbackTest() {
        when(admission.requireMember(WORKSPACE)).thenReturn(MEMBER);
    }

    @Test
    void aRequestOutsideTheLedgerOfThePersonIsNotFoundAndNothingIsWritten() {
        when(ledger.findRequestVersions(MEMBER.workspaceId(), MEMBER.userId(), request)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> submit.execute(WORKSPACE, request, FeedbackRating.HELPFUL, null))
                .isInstanceOf(CopilotRequestNotFoundException.class);

        verify(feedback, never()).upsert(any());
    }

    @Test
    void aMissingRatingIsInvalid() {
        assertThatThrownBy(() -> submit.execute(WORKSPACE, request, null, null))
                .isInstanceOf(InvalidValueException.class);
    }

    @Test
    void withoutAStoredMessageTheIntentIsNullAndTheVersionsComeFromTheLedger() {
        when(ledger.findRequestVersions(MEMBER.workspaceId(), MEMBER.userId(), request))
                .thenReturn(Optional.of(Map.of("intentPrompt", "intent-v2@abc")));
        when(conversations.findByRequest(any(), any(), any(), any())).thenReturn(Optional.empty());

        submit.execute(WORKSPACE, request, FeedbackRating.NOT_HELPFUL, FeedbackReason.OTHER);

        ArgumentCaptor<FeedbackStore.Feedback> saved = ArgumentCaptor.forClass(FeedbackStore.Feedback.class);
        verify(feedback).upsert(saved.capture());
        FeedbackStore.Feedback value = saved.getValue();
        assertThat(value.intent()).isNull();
        assertThat(value.versions()).containsEntry("intentPrompt", "intent-v2@abc");
        assertThat(value.rating()).isEqualTo(FeedbackRating.NOT_HELPFUL);
        assertThat(value.reason()).isEqualTo(FeedbackReason.OTHER);
        assertThat(value.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(90)));
        assertThat(meters.counter("shf.copilot.feedback", "rating", "NOT_HELPFUL").count()).isEqualTo(1);
    }

    @Test
    void aStoredMessageProvidesTheIntentAndItsVersions() {
        when(ledger.findRequestVersions(any(), any(), any())).thenReturn(Optional.of(Map.of("intentPrompt", "x")));
        CopilotResult result = new CopilotResult(request, ResultCode.OK, Intent.GOALS,
                RenderedAnswer.plain(AnswerType.ANALYSIS, "Resposta", List.of()), null, ValidationStatus.VALID, null,
                null, Map.of("answerPrompt", "answer-v2@abc"));
        when(conversations.findByRequest(MEMBER.workspaceId(), MEMBER.userId(), request, NOW)).thenReturn(Optional.of(
                new StoredMessage(UUID.randomUUID(), UUID.randomUUID(), "Pergunta", NOW, result)));

        submit.execute(WORKSPACE, request, FeedbackRating.HELPFUL, null);

        ArgumentCaptor<FeedbackStore.Feedback> saved = ArgumentCaptor.forClass(FeedbackStore.Feedback.class);
        verify(feedback).upsert(saved.capture());
        assertThat(saved.getValue().intent()).isEqualTo(Intent.GOALS);
        assertThat(saved.getValue().versions()).containsEntry("answerPrompt", "answer-v2@abc")
                .containsEntry("intentPrompt", "x");
    }
}
