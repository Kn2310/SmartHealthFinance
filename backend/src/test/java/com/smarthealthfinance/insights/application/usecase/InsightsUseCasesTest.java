package com.smarthealthfinance.insights.application.usecase;

import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.insights.application.port.InsightStore;
import com.smarthealthfinance.insights.application.service.InsightGenerator;
import com.smarthealthfinance.insights.domain.enums.InsightsFreshnessState;
import com.smarthealthfinance.insights.domain.model.Insight;
import com.smarthealthfinance.insights.domain.model.InsightsFreshness;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
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

/** Inbox da marca, ciclo do job, adiamento de falhas, retenção e freshness (ADR-0022 §3/§6/§8). */
class InsightsUseCasesTest {

    private static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final FakeStore store = new FakeStore();
    private final Set<String> inbox = new HashSet<>();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final WorkspaceId workspace = new WorkspaceId(UUID.randomUUID());

    @Test
    void markingIsIdempotentPerEvent() {
        MarkInsightsDirty mark = new MarkInsightsDirty((consumer, eventId) -> inbox.add(consumer + "/" + eventId),
                store, CLOCK, Duration.ofSeconds(10), Duration.ofSeconds(60), meters);
        UUID eventId = UUID.randomUUID();

        assertThat(mark.execute(eventId, workspace)).isTrue();
        assertThat(mark.execute(eventId, workspace)).isFalse();
        assertThat(store.calls).containsExactly("mark " + workspace + " quiet=PT10S max=PT1M");
        assertThat(inbox).containsExactly("insights.generation/" + eventId);
        assertThat(meters.counter("shf.insights.marked").count()).isEqualTo(1);
    }

    @Test
    void jobGeneratesEveryDueWorkspaceAndPostponesFailures() {
        WorkspaceId broken = new WorkspaceId(UUID.randomUUID());
        WorkspaceId other = new WorkspaceId(UUID.randomUUID());
        store.due.add(broken);
        store.due.add(other);
        List<WorkspaceId> generated = new ArrayList<>();
        InsightGenerator generator = new InsightGenerator(null, store, CLOCK, meters) {
            @Override
            public Outcome generate(WorkspaceId workspaceId) {
                if (workspaceId.equals(broken)) {
                    throw new IllegalStateException("boom");
                }
                generated.add(workspaceId);
                return new Outcome(0, 0, 0);
            }
        };

        int processed = new ProcessDueInsights(store, generator, transactions(), CLOCK, 50, Duration.ofSeconds(60))
                .execute();

        assertThat(processed).isEqualTo(2);
        assertThat(generated).containsExactly(other);
        assertThat(store.calls).contains("postpone " + broken + " " + NOW.plusSeconds(60));
    }

    @Test
    void jobStopsAtTheBatchSize() {
        for (int i = 0; i < 5; i++) {
            store.due.add(new WorkspaceId(UUID.randomUUID()));
        }
        InsightGenerator generator = new InsightGenerator(null, store, CLOCK, meters) {
            @Override
            public Outcome generate(WorkspaceId workspaceId) {
                return new Outcome(0, 0, 0);
            }
        };

        assertThat(new ProcessDueInsights(store, generator, transactions(), CLOCK, 3, Duration.ofSeconds(60))
                .execute()).isEqualTo(3);
        assertThat(store.due).hasSize(2);
    }

    @Test
    void purgeKeepsThirteenMonths() {
        new PurgeInsights(store, CLOCK).execute();
        assertThat(store.calls).containsExactly("purge " + Instant.parse("2025-09-16T12:00:00Z"));
    }

    @Test
    void rebuildMarksMissingOrAll() {
        new RebuildInsights(store, transactions(), CLOCK).execute(RebuildInsights.Scope.MISSING);
        new RebuildInsights(store, transactions(), CLOCK).execute(RebuildInsights.Scope.ALL);
        assertThat(store.calls).containsExactly("rebuild insights-v2 all=false", "rebuild insights-v2 all=true");
    }

    @ParameterizedTest(name = "built={0} tx={1} busy={2} fontes={3} asOf={4} → {5}")
    @CsvSource(nullValues = "-", value = {
            "false, false, false, false, -,          UP_TO_DATE",
            "false, true,  false, false, -,          NOT_BUILT",
            "true,  true,  false, false, 2026-10-16, UP_TO_DATE",
            "true,  true,  true,  false, 2026-10-16, UPDATING",
            "true,  true,  false, true,  2026-10-16, UPDATING",
            "true,  true,  false, false, 2026-10-15, UPDATING"})
    void freshness(boolean built, boolean hasTransactions, boolean busy, boolean sources, String asOf,
                   InsightsFreshnessState expected) {
        assertThat(InsightsFreshness.resolve(built, hasTransactions, busy, sources,
                asOf == null ? null : LocalDate.parse(asOf), LocalDate.parse("2026-10-16"), null, null).state())
                .isEqualTo(expected);
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

    private static final class FakeStore implements InsightStore {

        final List<String> calls = new ArrayList<>();
        final Deque<WorkspaceId> due = new ArrayDeque<>();

        @Override
        public void markDirty(WorkspaceId workspaceId, Instant now, Duration quiet, Duration maxWait) {
            calls.add("mark " + workspaceId + " quiet=" + quiet + " max=" + maxWait);
        }

        @Override
        public int markForRebuild(String algorithmVersion, boolean all, Instant now) {
            calls.add("rebuild " + algorithmVersion + " all=" + all);
            return 0;
        }

        @Override
        public Optional<WorkspaceId> claimDue(Instant now) {
            return Optional.ofNullable(due.poll());
        }

        @Override
        public List<Insight> findAll(WorkspaceId workspaceId) {
            return List.of();
        }

        @Override
        public void save(List<Insight> insights) {
        }

        @Override
        public void finishGeneration(WorkspaceId workspaceId, String algorithmVersion, Instant generatedAt,
                                     LocalDate asOf) {
        }

        @Override
        public void postpone(WorkspaceId workspaceId, Instant dueAt) {
            calls.add("postpone " + workspaceId + " " + dueAt);
        }

        @Override
        public Optional<Instant> oldestDirtySince() {
            return Optional.empty();
        }

        @Override
        public int purgeHiddenBefore(Instant before) {
            calls.add("purge " + before);
            return 0;
        }

        @Override
        public GenerationState state(WorkspaceId workspaceId, String algorithmVersion) {
            return new GenerationState(false, false, null, null, false, 0);
        }

        @Override
        public void lock(WorkspaceId workspaceId) {
        }

        @Override
        public Optional<Insight> findById(WorkspaceId workspaceId, UUID id) {
            return Optional.empty();
        }

        @Override
        public List<Insight> findPage(WorkspaceId workspaceId, String algorithmVersion,
                                      com.smarthealthfinance.insights.domain.enums.InsightStatus status,
                                      com.smarthealthfinance.insights.domain.enums.Importance importance,
                                      com.smarthealthfinance.insights.domain.enums.InsightType type, int offset,
                                      int limit) {
            return List.of();
        }

        @Override
        public long count(WorkspaceId workspaceId, String algorithmVersion,
                          com.smarthealthfinance.insights.domain.enums.InsightStatus status,
                          com.smarthealthfinance.insights.domain.enums.Importance importance,
                          com.smarthealthfinance.insights.domain.enums.InsightType type) {
            return 0;
        }

        @Override
        public ChipCounts activeCounts(WorkspaceId workspaceId, String algorithmVersion) {
            return new ChipCounts(0, 0, 0, 0, 0, 0);
        }
    }
}
