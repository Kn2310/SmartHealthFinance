package com.smarthealthfinance.copilot.application.usecase;

import com.smarthealthfinance.consents.application.port.ConsentReader;
import com.smarthealthfinance.consents.domain.enums.ConsentPurpose;
import com.smarthealthfinance.copilot.application.dto.ConversationCursor;
import com.smarthealthfinance.copilot.application.dto.ConversationPage;
import com.smarthealthfinance.copilot.application.dto.ConversationSummary;
import com.smarthealthfinance.copilot.application.exception.ConversationNotFoundException;
import com.smarthealthfinance.copilot.application.port.ConversationStore;
import com.smarthealthfinance.copilot.application.service.CopilotAdmission;
import com.smarthealthfinance.identity.domain.valueobject.UserId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.InvalidValueException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Listagem com cursor, limites e exclusão do histórico (ADR-0040 §2, §6). */
class ManageConversationsTest {

    private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");
    private static final UUID WORKSPACE = UUID.randomUUID();
    private static final CopilotAdmission.Member MEMBER = new CopilotAdmission.Member(new WorkspaceId(WORKSPACE),
            new UserId(UUID.randomUUID()));

    private final CopilotAdmission admission = mock(CopilotAdmission.class);
    private final ConsentReader consents = mock(ConsentReader.class);
    private final ConversationStore store = mock(ConversationStore.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ManageConversations conversations = new ManageConversations(admission, consents, store,
            Clock.fixed(NOW, ZoneOffset.UTC), meters);

    ManageConversationsTest() {
        when(admission.requireMember(WORKSPACE)).thenReturn(MEMBER);
    }

    private void history(boolean active) {
        when(consents.isActive(MEMBER.workspaceId(), MEMBER.userId(), ConsentPurpose.COPILOT_HISTORY))
                .thenReturn(active);
    }

    private static List<ConversationSummary> summaries(int count) {
        List<ConversationSummary> items = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            items.add(new ConversationSummary(UUID.randomUUID(), "t" + i, NOW.minusSeconds(100 + i),
                    NOW.minusSeconds(i), 1));
        }
        return items;
    }

    @Test
    void withoutHistoryTheListIsDisabledAndEmptyAndTheStoreIsNeverRead() {
        history(false);

        ConversationPage page = conversations.list(WORKSPACE, null, null);

        assertThat(page.historyEnabled()).isFalse();
        assertThat(page.items()).isEmpty();
        assertThat(page.nextCursor()).isNull();
        verify(store, never()).list(any(), any(), any(), any(), anyInt());
    }

    @Test
    void asksForOneMoreThanTheLimitAndBuildsTheNextCursorFromTheLastItem() {
        history(true);
        List<ConversationSummary> fetched = summaries(4);
        when(store.list(eq(MEMBER.workspaceId()), eq(MEMBER.userId()), eq(NOW), any(), eq(4))).thenReturn(fetched);

        ConversationPage page = conversations.list(WORKSPACE, 3, null);

        assertThat(page.historyEnabled()).isTrue();
        assertThat(page.items()).containsExactlyElementsOf(fetched.subList(0, 3));
        ConversationSummary last = fetched.get(2);
        assertThat(ConversationCursor.parse(page.nextCursor()))
                .isEqualTo(new ConversationCursor(last.lastMessageAt(), last.id()));
    }

    @Test
    void theLastPageHasNoCursorTheDefaultLimitIs20AndLimitsAreClamped() {
        history(true);
        when(store.list(any(), any(), any(), any(), eq(21))).thenReturn(summaries(2));
        when(store.list(any(), any(), any(), any(), eq(51))).thenReturn(List.of());
        when(store.list(any(), any(), any(), any(), eq(2))).thenReturn(List.of());

        assertThat(conversations.list(WORKSPACE, null, null).nextCursor()).isNull();
        conversations.list(WORKSPACE, 500, null);
        conversations.list(WORKSPACE, 0, null);

        verify(store).list(any(), any(), any(), any(), eq(21));
        verify(store).list(any(), any(), any(), any(), eq(51));
        verify(store).list(any(), any(), any(), any(), eq(2));
    }

    @Test
    void theCursorIsPassedToTheStoreAndAnInvalidOneIsRejectedBeforeAnything() {
        history(true);
        ConversationCursor cursor = new ConversationCursor(NOW.minusSeconds(60), UUID.randomUUID());
        when(store.list(any(), any(), any(), any(), eq(21))).thenReturn(List.of());

        conversations.list(WORKSPACE, null, cursor.encode());

        ArgumentCaptor<ConversationCursor> passed = ArgumentCaptor.forClass(ConversationCursor.class);
        verify(store).list(any(), any(), any(), passed.capture(), eq(21));
        assertThat(passed.getValue()).isEqualTo(cursor);
        assertThatThrownBy(() -> conversations.list(WORKSPACE, null, "lixo"))
                .isInstanceOf(InvalidValueException.class);
    }

    @Test
    void readingOrDeletingOneNeedsHistoryAndAnExistingConversation() {
        UUID id = UUID.randomUUID();
        history(false);
        assertThatThrownBy(() -> conversations.get(WORKSPACE, id)).isInstanceOf(ConversationNotFoundException.class);
        assertThatThrownBy(() -> conversations.delete(WORKSPACE, id))
                .isInstanceOf(ConversationNotFoundException.class);
        verify(store, never()).delete(any(), any(), any(), any());

        history(true);
        when(store.find(any(), any(), eq(id), any())).thenReturn(Optional.empty());
        when(store.delete(any(), any(), eq(id), any())).thenReturn(false);
        assertThatThrownBy(() -> conversations.get(WORKSPACE, id)).isInstanceOf(ConversationNotFoundException.class);
        assertThatThrownBy(() -> conversations.delete(WORKSPACE, id))
                .isInstanceOf(ConversationNotFoundException.class);

        when(store.delete(MEMBER.workspaceId(), MEMBER.userId(), id, NOW)).thenReturn(true);
        conversations.delete(WORKSPACE, id);
        assertThat(meters.counter("shf.copilot.history.deleted", "cause", "USER").count()).isEqualTo(1);
    }

    @Test
    void deletingAllIsIdempotentAndCountsOnlyWhatWasDeleted() {
        when(store.deleteAll(MEMBER.workspaceId(), MEMBER.userId())).thenReturn(3).thenReturn(0);

        conversations.deleteAll(WORKSPACE);
        conversations.deleteAll(WORKSPACE);

        assertThat(meters.counter("shf.copilot.history.deleted", "cause", "USER").count()).isEqualTo(3);
    }
}
