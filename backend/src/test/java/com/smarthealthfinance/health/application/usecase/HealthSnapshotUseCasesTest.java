package com.smarthealthfinance.health.application.usecase;

import com.smarthealthfinance.health.application.port.HealthSnapshotStore;
import com.smarthealthfinance.health.application.service.HealthSnapshotCalculator;
import com.smarthealthfinance.health.domain.model.HealthResult;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Inbox da marca, ciclo do job e adiamento de falhas (ADR-0018 §3). */
class HealthSnapshotUseCasesTest {

    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final FakeStore store = new FakeStore();
    private final Set<String> inbox = new HashSet<>();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final WorkspaceId workspace = new WorkspaceId(UUID.randomUUID());

    @Test
    void markingIsIdempotentPerEvent() {
        MarkHealthDirty mark = new MarkHealthDirty((consumer, eventId) -> inbox.add(consumer + "/" + eventId), store,
                CLOCK, Duration.ofSeconds(10), Duration.ofSeconds(60), meters);
        UUID eventId = UUID.randomUUID();

        assertThat(mark.execute(eventId, workspace)).isTrue();
        assertThat(mark.execute(eventId, workspace)).isFalse();

        assertThat(store.calls).containsExactly("mark " + workspace + " quiet=PT10S max=PT1M");
        assertThat(inbox).containsExactly("health.snapshot/" + eventId);
        assertThat(meters.counter("shf.health.snapshot.marked").count()).isEqualTo(1);
    }

    @Test
    void jobRecalculatesEveryDueWorkspaceAndPostponesFailures() {
        WorkspaceId broken = new WorkspaceId(UUID.randomUUID());
        WorkspaceId other = new WorkspaceId(UUID.randomUUID());
        store.due.add(broken);
        store.due.add(other);
        List<WorkspaceId> recalculated = new ArrayList<>();
        HealthSnapshotCalculator calculator = new HealthSnapshotCalculator(null, store, CLOCK, meters, null) {
            @Override
            public List<HealthResult> recalculate(WorkspaceId workspaceId) {
                if (workspaceId.equals(broken)) {
                    throw new IllegalStateException("boom");
                }
                recalculated.add(workspaceId);
                return List.of();
            }
        };

        int processed = new ProcessDueHealthSnapshots(store, calculator, transactions(), CLOCK, 50,
                Duration.ofSeconds(60)).execute();

        assertThat(processed).isEqualTo(2);
        assertThat(recalculated).containsExactly(other);
        assertThat(store.calls).contains("postpone " + broken + " " + NOW.plusSeconds(60));
    }

    @Test
    void jobStopsAtTheBatchSize() {
        for (int i = 0; i < 5; i++) {
            store.due.add(new WorkspaceId(UUID.randomUUID()));
        }
        HealthSnapshotCalculator calculator = new HealthSnapshotCalculator(null, store, CLOCK, meters, null) {
            @Override
            public List<HealthResult> recalculate(WorkspaceId workspaceId) {
                return List.of();
            }
        };

        assertThat(new ProcessDueHealthSnapshots(store, calculator, transactions(), CLOCK, 3, Duration.ofSeconds(60))
                .execute()).isEqualTo(3);
        assertThat(store.due).hasSize(2);
    }

    private static TransactionTemplate transactions() {
        return new TransactionTemplate(new AbstractPlatformTransactionManager() {
            @Override
            protected Object doGetTransaction() {
                return new Object();
            }

            @Override
            protected void doBegin(Object transaction, TransactionDefinition definition) {
            }

            @Override
            protected void doCommit(DefaultTransactionStatus status) {
            }

            @Override
            protected void doRollback(DefaultTransactionStatus status) {
            }
        });
    }

    private static final class FakeStore implements HealthSnapshotStore {

        final List<String> calls = new ArrayList<>();
        final Deque<WorkspaceId> due = new ArrayDeque<>();

        @Override
        public void markDirty(WorkspaceId workspaceId, Instant now, Duration quiet, Duration maxWait) {
            calls.add("mark " + workspaceId + " quiet=" + quiet + " max=" + maxWait);
        }

        @Override
        public int markAllWithSnapshotsDirty(Instant now, Duration quiet, Duration maxWait) {
            return 0;
        }

        @Override
        public Optional<WorkspaceId> claimDue(Instant now) {
            return Optional.ofNullable(due.poll());
        }

        @Override
        public void lock(WorkspaceId workspaceId) {
        }

        @Override
        public void replace(WorkspaceId workspaceId, String algorithmVersion, List<HealthResult> results,
                            Instant computedAt) {
        }

        @Override
        public Optional<Instant> oldestDirtySince() {
            return Optional.empty();
        }

        @Override
        public void postpone(WorkspaceId workspaceId, Instant dueAt) {
            calls.add("postpone " + workspaceId + " " + dueAt);
        }

        @Override
        public List<WorkspaceId> workspacesToRebuild(String algorithmVersion, boolean all) {
            return List.of();
        }

        @Override
        public Optional<HealthResult> find(WorkspaceId workspaceId, String algorithmVersion, YearMonth month) {
            return Optional.empty();
        }

        @Override
        public List<HealthResult> findMonths(WorkspaceId workspaceId, String algorithmVersion, YearMonth from,
                                             YearMonth to) {
            return List.of();
        }

        @Override
        public SnapshotState state(WorkspaceId workspaceId, String algorithmVersion) {
            return new SnapshotState(false, null, false, null, false, 0);
        }
    }
}
