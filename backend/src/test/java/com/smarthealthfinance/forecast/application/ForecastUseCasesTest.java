package com.smarthealthfinance.forecast.application;

import com.smarthealthfinance.analytics.application.port.AnalyticsReadModel;
import com.smarthealthfinance.analytics.domain.enums.ProjectionState;
import com.smarthealthfinance.analytics.domain.model.ProjectionFreshness;
import com.smarthealthfinance.forecast.application.ForecastFakes.FakeStore;
import com.smarthealthfinance.forecast.application.ForecastFakes.InMemorySeries;
import com.smarthealthfinance.forecast.application.ForecastFakes.StubLoader;
import com.smarthealthfinance.forecast.application.port.ForecastReader.SnapshotState;
import com.smarthealthfinance.forecast.application.service.ForecastSnapshotCalculator;
import com.smarthealthfinance.forecast.application.usecase.GetForecastStatus;
import com.smarthealthfinance.forecast.application.usecase.MarkAllForecastDirty;
import com.smarthealthfinance.forecast.application.usecase.MarkForecastDirty;
import com.smarthealthfinance.forecast.application.usecase.ProcessDueForecastSnapshots;
import com.smarthealthfinance.forecast.application.usecase.RebuildForecastSnapshots;
import com.smarthealthfinance.forecast.domain.enums.ForecastFreshnessState;
import com.smarthealthfinance.forecast.domain.model.ForecastFreshness;
import com.smarthealthfinance.forecast.domain.model.ForecastInput;
import com.smarthealthfinance.forecast.domain.model.ForecastResult;
import com.smarthealthfinance.identity.application.InMemoryUserRepository;
import com.smarthealthfinance.identity.application.InMemoryWorkspaceRepository;
import com.smarthealthfinance.identity.application.dto.AuthenticatedIdentity;
import com.smarthealthfinance.identity.application.exception.WorkspaceNotFoundException;
import com.smarthealthfinance.identity.application.service.CurrentUserService;
import com.smarthealthfinance.identity.application.service.WorkspaceAccessGuard;
import com.smarthealthfinance.identity.domain.model.User;
import com.smarthealthfinance.identity.domain.model.Workspace;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.Money;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.smarthealthfinance.identity.IdentityFixtures.activeUser;
import static com.smarthealthfinance.identity.IdentityFixtures.personalWorkspace;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Inbox da marca, ciclo do job, rebuild, calendário e status (ADR-0031 §3, §6, §8, §9). */
class ForecastUseCasesTest {

    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Currency BRL = Currency.getInstance("BRL");

    private final FakeStore store = new FakeStore();
    private final Set<String> inbox = new HashSet<>();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final WorkspaceId workspace = new WorkspaceId(UUID.randomUUID());

    @Test
    void markingIsIdempotentPerEvent() {
        MarkForecastDirty mark = new MarkForecastDirty((consumer, eventId) -> inbox.add(consumer + "/" + eventId),
                store, CLOCK, Duration.ofSeconds(10), Duration.ofSeconds(60), meters);
        UUID eventId = UUID.randomUUID();

        assertThat(mark.execute(eventId, workspace)).isTrue();
        assertThat(mark.execute(eventId, workspace)).isFalse();

        assertThat(store.calls).containsExactly("mark " + workspace + " quiet=PT10S max=PT1M");
        assertThat(inbox).containsExactly("forecast.snapshot/" + eventId);
        assertThat(meters.counter("shf.forecast.snapshot.marked").count()).isEqualTo(1);
    }

    @Test
    void dailyJobMarksEveryWorkspaceWithASnapshot() {
        assertThat(new MarkAllForecastDirty(store, CLOCK, Duration.ofSeconds(10), Duration.ofSeconds(60)).execute())
                .isEqualTo(3);
        assertThat(store.calls).containsExactly("mark-all");
    }

    @Test
    void jobRecalculatesEveryDueWorkspaceAndPostponesFailures() {
        WorkspaceId broken = new WorkspaceId(UUID.randomUUID());
        WorkspaceId other = new WorkspaceId(UUID.randomUUID());
        store.due.add(broken);
        store.due.add(other);
        List<WorkspaceId> recalculated = new ArrayList<>();
        ForecastSnapshotCalculator calculator = new ForecastSnapshotCalculator(null, store, null, null, CLOCK, meters,
                null) {
            @Override
            public ForecastResult recalculate(WorkspaceId workspaceId) {
                if (workspaceId.equals(broken)) {
                    throw new IllegalStateException("boom");
                }
                recalculated.add(workspaceId);
                return null;
            }
        };

        int processed = new ProcessDueForecastSnapshots(store, calculator, transactions(), CLOCK, 50,
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
        ForecastSnapshotCalculator calculator = new ForecastSnapshotCalculator(null, store, null, null, CLOCK, meters,
                null) {
            @Override
            public ForecastResult recalculate(WorkspaceId workspaceId) {
                return null;
            }
        };

        assertThat(new ProcessDueForecastSnapshots(store, calculator, transactions(), CLOCK, 3,
                Duration.ofSeconds(60)).execute()).isEqualTo(3);
        assertThat(store.due).hasSize(2);
    }

    @Test
    void rebuildLocksAndRecalculatesEachTargetWithTheSameFunction() {
        WorkspaceId other = new WorkspaceId(UUID.randomUUID());
        store.rebuildTargets.addAll(List.of(workspace, other));
        List<WorkspaceId> recalculated = new ArrayList<>();
        ForecastSnapshotCalculator calculator = new ForecastSnapshotCalculator(null, store, null, null, CLOCK, meters,
                null) {
            @Override
            public ForecastResult recalculate(WorkspaceId workspaceId) {
                recalculated.add(workspaceId);
                return null;
            }
        };
        RebuildForecastSnapshots rebuild = new RebuildForecastSnapshots(store, calculator, transactions());

        assertThat(rebuild.execute(RebuildForecastSnapshots.Scope.MISSING)).isEqualTo(2);
        assertThat(rebuild.execute(RebuildForecastSnapshots.Scope.ALL)).isEqualTo(2);

        assertThat(recalculated).containsExactly(workspace, other, workspace, other);
        assertThat(store.calls).contains("targets all=false", "targets all=true", "lock " + workspace);
    }

    @Test
    void rebuildSkipsAFailingWorkspaceAndContinuesWithTheOthers() {
        WorkspaceId broken = new WorkspaceId(UUID.randomUUID());
        WorkspaceId other = new WorkspaceId(UUID.randomUUID());
        store.rebuildTargets.addAll(List.of(broken, other));
        List<WorkspaceId> recalculated = new ArrayList<>();
        ForecastSnapshotCalculator calculator = new ForecastSnapshotCalculator(null, store, null, null, CLOCK, meters,
                null) {
            @Override
            public ForecastResult recalculate(WorkspaceId workspaceId) {
                if (workspaceId.equals(broken)) {
                    throw new IllegalStateException("boom");
                }
                recalculated.add(workspaceId);
                return null;
            }
        };

        int rebuilt = new RebuildForecastSnapshots(store, calculator, transactions())
                .execute(RebuildForecastSnapshots.Scope.MISSING);

        assertThat(rebuilt).isEqualTo(1);
        assertThat(recalculated).containsExactly(other);
    }

    // --- status ---

    @Test
    void freshnessTable() {
        LocalDate today = LocalDate.parse("2026-10-15");
        Instant at = NOW;
        assertThat(state(false, true, false, 0, false, null, today)).isEqualTo(ForecastFreshnessState.NOT_BUILT);
        assertThat(state(false, false, false, 0, false, null, today)).isEqualTo(ForecastFreshnessState.UP_TO_DATE);
        assertThat(state(true, true, false, 0, false, today, today)).isEqualTo(ForecastFreshnessState.UP_TO_DATE);
        assertThat(state(true, true, true, 0, false, today, today)).isEqualTo(ForecastFreshnessState.RECALCULATING);
        assertThat(state(true, true, false, 2, false, today, today)).isEqualTo(ForecastFreshnessState.RECALCULATING);
        assertThat(state(true, true, false, 0, true, today, today)).isEqualTo(ForecastFreshnessState.RECALCULATING);
        assertThat(state(true, true, false, 0, false, today.minusDays(1), today))
                .isEqualTo(ForecastFreshnessState.RECALCULATING);
        assertThat(ForecastFreshness.resolve(true, true, false, 0, false, today, today, at, null))
                .extracting(ForecastFreshness::computedAt, ForecastFreshness::asOf).containsExactly(at, today);
    }

    private static ForecastFreshnessState state(boolean built, boolean hasTransactions, boolean dirty, int pending,
                                                boolean analyticsUpdating, LocalDate asOf, LocalDate today) {
        return ForecastFreshness.resolve(built, hasTransactions, dirty, pending, analyticsUpdating, asOf, today, null,
                null).state();
    }

    @Test
    void statusMarksAWorkspaceWhoseSnapshotIsFromAnEarlierDayAndReportsRecalculating() {
        Fixture fx = new Fixture();
        store.state = new SnapshotState(false, NOW.minusSeconds(60), true, LocalDate.parse("2026-10-14"), true, 0);

        ForecastFreshness freshness = fx.status.execute(fx.anaWorkspace.id().value());

        assertThat(freshness.state()).isEqualTo(ForecastFreshnessState.RECALCULATING);
        assertThat(freshness.asOf()).isEqualTo(LocalDate.parse("2026-10-14"));
        assertThat(store.calls).containsExactly("mark " + fx.anaWorkspace.id() + " quiet=PT10S max=PT1M");
    }

    @Test
    void statusIsUpToDateWithoutMarkingAndOtherUsersSeeNothing() {
        Fixture fx = new Fixture();
        store.state = new SnapshotState(false, NOW, true, LocalDate.parse("2026-10-15"), true, 0);

        assertThat(fx.status.execute(fx.anaWorkspace.id().value()).state()).isEqualTo(ForecastFreshnessState.UP_TO_DATE);
        assertThat(store.calls).isEmpty();

        fx.actAs(fx.bob);
        assertThatThrownBy(() -> fx.status.execute(fx.anaWorkspace.id().value())).isInstanceOf(WorkspaceNotFoundException.class);
        assertThat(store.calls).isEmpty();
    }

    @Test
    void analyticsStillUpdatingMeansRecalculating() {
        Fixture fx = new Fixture();
        fx.analyticsState = ProjectionState.UPDATING;
        store.state = new SnapshotState(false, NOW, true, LocalDate.parse("2026-10-15"), true, 0);

        assertThat(fx.status.execute(fx.anaWorkspace.id().value()).state()).isEqualTo(ForecastFreshnessState.RECALCULATING);
    }

    // --- infraestrutura ---

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

    /** Ana e Bob, cada um com o seu Workspace. */
    private final class Fixture {
        final InMemoryUserRepository users = new InMemoryUserRepository();
        final InMemoryWorkspaceRepository workspaces = new InMemoryWorkspaceRepository();
        final User ana = activeUser("sub-ana");
        final User bob = activeUser("sub-bob");
        ProjectionState analyticsState = ProjectionState.UP_TO_DATE;
        private User acting;
        final GetForecastStatus status;
        final Workspace anaWorkspace;

        Fixture() {
            User anaUser = ana;
            users.store(anaUser);
            users.store(bob);
            anaWorkspace = personalWorkspace(anaUser);
            workspaces.store(anaWorkspace);
            workspaces.store(personalWorkspace(bob));
            acting = anaUser;
            AnalyticsReadModel analytics = (AnalyticsReadModel) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[] {AnalyticsReadModel.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("freshness")) {
                            return new ProjectionFreshness(analyticsState, NOW, 0);
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
            StubLoader loader = new StubLoader(new InMemorySeries());
            loader.input = new ForecastInput(LocalDate.parse("2026-10-15"), BRL, Optional.empty(), List.of(),
                    List.of(), Money.zero(BRL), List.of(), List.of());
            status = new GetForecastStatus(new CurrentUserService(
                    () -> new AuthenticatedIdentity(acting.externalIdentity(), acting.email().value(), null, null),
                    users), new WorkspaceAccessGuard(workspaces), store, analytics, loader, CLOCK,
                    Duration.ofSeconds(10), Duration.ofSeconds(60));
        }

        void actAs(User user) {
            acting = user;
        }
    }
}
