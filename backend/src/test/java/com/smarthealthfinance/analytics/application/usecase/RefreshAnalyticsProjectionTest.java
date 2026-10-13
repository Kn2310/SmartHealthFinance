package com.smarthealthfinance.analytics.application.usecase;

import com.smarthealthfinance.analytics.application.port.AnalyticsProjectionStore;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Inbox, autocorreção por rebuild e coalescência do consumidor (ADR-0014 §3/§4). */
class RefreshAnalyticsProjectionTest {

    private static final Instant NOW = Instant.parse("2026-09-27T12:00:00Z");
    private static final YearMonth AUG = YearMonth.of(2026, 8);
    private static final YearMonth SEP = YearMonth.of(2026, 9);

    private final FakeStore store = new FakeStore();
    private final Set<String> inbox = new HashSet<>();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final RefreshAnalyticsProjection refresh = new RefreshAnalyticsProjection(
            (consumer, eventId) -> inbox.add(consumer + "/" + eventId), store, Clock.fixed(NOW, ZoneOffset.UTC),
            Duration.ofSeconds(2), meters);
    private final WorkspaceId workspace = new WorkspaceId(UUID.randomUUID());

    @Test
    void firstEventOfAWorkspaceWithoutProjectionRebuildsEverything() {
        var outcome = refresh.execute(UUID.randomUUID(), NOW.minusSeconds(1), workspace, Set.of(SEP));

        assertThat(outcome).isEqualTo(RefreshAnalyticsProjection.Outcome.REBUILT);
        assertThat(store.calls).containsExactly("lock", "rebuild v" + RebuildAnalyticsProjection.VERSION);
    }

    @Test
    void projectionOfAnotherVersionIsRebuilt() {
        store.version = OptionalInt.of(RebuildAnalyticsProjection.VERSION + 1);

        assertThat(refresh.execute(UUID.randomUUID(), NOW, workspace, Set.of(SEP)))
                .isEqualTo(RefreshAnalyticsProjection.Outcome.REBUILT);
    }

    @Test
    void recomputesEveryAffectedMonthInOrder() {
        store.version = OptionalInt.of(RebuildAnalyticsProjection.VERSION);

        var outcome = refresh.execute(UUID.randomUUID(), NOW.minusSeconds(1), workspace, Set.of(SEP, AUG));

        assertThat(outcome).isEqualTo(RefreshAnalyticsProjection.Outcome.REFRESHED);
        assertThat(store.calls).containsExactly("lock", "recompute 2026-08", "recompute 2026-09");
        assertThat(store.refreshed).containsEntry(SEP, NOW).containsEntry(AUG, NOW);
    }

    @Test
    void monthRecalculatedAfterThePublicationIsCoalesced() {
        store.version = OptionalInt.of(RebuildAnalyticsProjection.VERSION);
        Instant publishedAt = NOW.minusSeconds(60);
        store.refreshed.put(SEP, publishedAt.plusSeconds(3));
        store.refreshed.put(AUG, publishedAt.plusSeconds(1));

        refresh.execute(UUID.randomUUID(), publishedAt, workspace, Set.of(SEP, AUG));

        // Setembro foi recalculado 3 s depois da publicação (fora da margem de 2 s): já reflete o fato.
        // Agosto, só 1 s depois: dentro da margem de relógio, então recalcula de novo por segurança.
        assertThat(store.calls).containsExactly("lock", "recompute 2026-08");
        assertThat(meters.counter("shf.analytics.projection.months", "result", "coalesced").count()).isEqualTo(1);
        assertThat(meters.counter("shf.analytics.projection.months", "result", "recomputed").count()).isEqualTo(1);
    }

    @Test
    void redeliveryOfTheSameEventHasNoEffect() {
        store.version = OptionalInt.of(RebuildAnalyticsProjection.VERSION);
        UUID eventId = UUID.randomUUID();
        refresh.execute(eventId, NOW, workspace, Set.of(SEP));
        store.calls.clear();

        assertThat(refresh.execute(eventId, NOW, workspace, Set.of(SEP)))
                .isEqualTo(RefreshAnalyticsProjection.Outcome.DUPLICATE);
        assertThat(store.calls).isEmpty();
    }

    @Test
    void eventWithoutMonthsIsOnlyMarkedAsProcessed() {
        store.version = OptionalInt.of(RebuildAnalyticsProjection.VERSION);

        refresh.execute(UUID.randomUUID(), NOW, workspace, Set.of());

        assertThat(store.calls).containsExactly("lock");
        assertThat(inbox).hasSize(1);
    }

    @Test
    void recordsTheOutcomeTimer() {
        refresh.execute(UUID.randomUUID(), NOW, workspace, Set.of(SEP));

        assertThat(meters.find("shf.analytics.projection.refresh").tag("outcome", "REBUILT").timer()).isNotNull();
    }

    private static final class FakeStore implements AnalyticsProjectionStore {

        OptionalInt version = OptionalInt.empty();
        final Map<YearMonth, Instant> refreshed = new HashMap<>();
        final List<String> calls = new ArrayList<>();

        @Override
        public OptionalInt lockWorkspace(WorkspaceId workspaceId) {
            calls.add("lock");
            return version;
        }

        @Override
        public Optional<Instant> monthRefreshedAt(WorkspaceId workspaceId, YearMonth month) {
            return Optional.ofNullable(refreshed.get(month));
        }

        @Override
        public void recomputeMonth(WorkspaceId workspaceId, YearMonth month, Instant refreshedAt) {
            calls.add("recompute " + month);
            refreshed.put(month, refreshedAt);
        }

        @Override
        public void rebuild(WorkspaceId workspaceId, int newVersion, Instant rebuiltAt) {
            calls.add("rebuild v" + newVersion);
            version = OptionalInt.of(newVersion);
        }

        @Override
        public List<WorkspaceId> workspacesToRebuild(int currentVersion, boolean all) {
            return List.of();
        }
    }
}
