package com.smarthealthfinance.forecast.application;

import com.smarthealthfinance.forecast.application.port.ForecastSnapshotStore;
import com.smarthealthfinance.forecast.application.port.MonthOutcomeStore;
import com.smarthealthfinance.forecast.application.port.RecurringSeriesRepository;
import com.smarthealthfinance.forecast.application.service.ForecastInputLoader;
import com.smarthealthfinance.forecast.domain.enums.SeriesDirection;
import com.smarthealthfinance.forecast.domain.model.ForecastInput;
import com.smarthealthfinance.forecast.domain.model.ForecastResult;
import com.smarthealthfinance.forecast.domain.model.MonthOutcome;
import com.smarthealthfinance.forecast.domain.model.RecurringSeries;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Fakes em memória das portas do Forecast, com a semântica documentada dos adapters JDBC. */
public final class ForecastFakes {

    private ForecastFakes() {
    }

    /** Entrada fixada pelo teste: nada de portas reais. {@code existingSeries} vem do repositório fake. */
    public static final class StubLoader extends ForecastInputLoader {

        public ForecastInput input;
        private final RecurringSeriesRepository series;

        public StubLoader(RecurringSeriesRepository series) {
            super(null, null, null, null, null, series, Clock.systemUTC(), ZoneOffset.UTC);
            this.series = series;
        }

        @Override
        public LocalDate today() {
            return input.today();
        }

        @Override
        public ForecastInput load(WorkspaceId workspaceId) {
            return new ForecastInput(input.today(), input.currency(), input.firstActivity(), input.entries(),
                    series.findAll(workspaceId), input.totalBalance(), input.cards(), input.invoiceEvents());
        }
    }

    public static final class FakeStore implements ForecastSnapshotStore {

        public final List<String> calls = new ArrayList<>();
        public final Deque<WorkspaceId> due = new ArrayDeque<>();
        public final Map<String, ForecastResult> snapshots = new HashMap<>();
        public SnapshotState state = new SnapshotState(false, null, false, null, false, 0);

        @Override
        public void markDirty(WorkspaceId workspaceId, Instant now, Duration quiet, Duration maxWait) {
            calls.add("mark " + workspaceId + " quiet=" + quiet + " max=" + maxWait);
        }

        @Override
        public void markDueNow(WorkspaceId workspaceId, Instant now) {
            calls.add("due-now " + workspaceId);
        }

        @Override
        public int markAllWithSnapshotsDirty(Instant now, Duration quiet, Duration maxWait) {
            calls.add("mark-all");
            return 3;
        }

        @Override
        public Optional<WorkspaceId> claimDue(Instant now) {
            return Optional.ofNullable(due.poll());
        }

        @Override
        public void lock(WorkspaceId workspaceId) {
            calls.add("lock " + workspaceId);
        }

        @Override
        public void replace(WorkspaceId workspaceId, ForecastResult result, Instant computedAt) {
            snapshots.put(workspaceId + "/" + result.algorithmVersion(), result);
            calls.add("replace " + workspaceId);
        }

        @Override
        public Optional<Instant> oldestDirtySince() {
            return Optional.empty();
        }

        @Override
        public void postpone(WorkspaceId workspaceId, Instant dueAt) {
            calls.add("postpone " + workspaceId + " " + dueAt);
        }

        public final List<WorkspaceId> rebuildTargets = new ArrayList<>();

        @Override
        public List<WorkspaceId> workspacesToRebuild(String algorithmVersion, boolean all) {
            calls.add("targets all=" + all);
            return rebuildTargets;
        }

        @Override
        public Optional<ForecastResult> find(WorkspaceId workspaceId, String algorithmVersion) {
            return Optional.ofNullable(snapshots.get(workspaceId + "/" + algorithmVersion));
        }

        @Override
        public SnapshotState state(WorkspaceId workspaceId, String algorithmVersion) {
            return state;
        }
    }

    /** Espelha {@code JdbcRecurringSeriesRepository}: a detecção só escreve as colunas detectadas; nada é apagado. */
    public static final class InMemorySeries implements RecurringSeriesRepository {

        public final Map<String, RecurringSeries> rows = new LinkedHashMap<>();
        public final List<String> calls = new ArrayList<>();

        private static String id(WorkspaceId ws, SeriesDirection direction, String key) {
            return ws + "/" + direction + "/" + key;
        }

        public void put(WorkspaceId ws, RecurringSeries series) {
            rows.put(id(ws, series.direction(), series.key()), series);
        }

        @Override
        public List<RecurringSeries> findAll(WorkspaceId workspaceId) {
            return rows.entrySet().stream().filter(e -> e.getKey().startsWith(workspaceId + "/"))
                    .map(Map.Entry::getValue)
                    .sorted(Comparator.comparing(RecurringSeries::direction).thenComparing(RecurringSeries::key))
                    .toList();
        }

        @Override
        public Optional<RecurringSeries> find(WorkspaceId workspaceId, SeriesDirection direction, String key) {
            return Optional.ofNullable(rows.get(id(workspaceId, direction, key)));
        }

        @Override
        public Optional<RecurringSeries> findForUpdate(WorkspaceId workspaceId, SeriesDirection direction,
                                                       String key) {
            calls.add("lock-series " + key);
            return find(workspaceId, direction, key);
        }

        @Override
        public void upsertDetected(WorkspaceId workspaceId, Collection<RecurringSeries> series, Instant now) {
            for (RecurringSeries s : series) {
                String id = id(workspaceId, s.direction(), s.key());
                RecurringSeries existing = rows.get(id);
                rows.put(id, existing == null ? s : existing.withDetection(s));
            }
        }

        @Override
        public void saveUserState(WorkspaceId workspaceId, RecurringSeries series, Instant now) {
            calls.add("save " + series.key() + " " + series.status());
            String id = id(workspaceId, series.direction(), series.key());
            RecurringSeries existing = rows.get(id);
            rows.put(id, new RecurringSeries(existing.direction(), existing.key(), existing.label(),
                    existing.channel(), existing.periodicity(), existing.expectedDay(), existing.expectedAmount(),
                    existing.amountRange(), existing.confidence(), existing.occurrences(),
                    existing.algorithmVersion(), series.status(), existing.origin(), series.userExpectedAmount(),
                    series.userExpectedDay(), series.statusChangedAt()));
        }
    }

    /** Espelha {@code JdbcMonthOutcomeStore}: congelar não sobrescreve; liquidado é imutável. */
    public static final class InMemoryOutcomes implements MonthOutcomeStore {

        public final Map<YearMonth, Stored> rows = new LinkedHashMap<>();
        public int freezeAttempts;

        @Override
        public boolean freeze(WorkspaceId workspaceId, MonthOutcome frozen, Instant now) {
            freezeAttempts++;
            if (rows.containsKey(frozen.month())) {
                return false;
            }
            rows.put(frozen.month(), new Stored(frozen, now, null));
            return true;
        }

        @Override
        public boolean reconcile(WorkspaceId workspaceId, MonthOutcome reconciled) {
            Stored stored = rows.get(reconciled.month());
            if (stored == null || stored.settled()) {
                return false;
            }
            MonthOutcome base = stored.outcome();
            rows.put(base.month(), new Stored(new MonthOutcome(base.month(), base.algorithmVersion(),
                    base.projected(), base.projectedLow(), base.projectedHigh(), base.confidence(),
                    reconciled.realized(), reconciled.error()), stored.frozenAt(), null));
            return true;
        }

        @Override
        public boolean settle(WorkspaceId workspaceId, YearMonth month, String algorithmVersion, Instant now) {
            Stored stored = rows.get(month);
            if (stored == null || stored.settled()) {
                return false;
            }
            rows.put(month, new Stored(stored.outcome(), stored.frozenAt(), now));
            return true;
        }

        @Override
        public Optional<Stored> find(WorkspaceId workspaceId, YearMonth month, String algorithmVersion) {
            return Optional.ofNullable(rows.get(month));
        }

        @Override
        public List<Stored> findUnsettled(WorkspaceId workspaceId, String algorithmVersion) {
            return rows.values().stream().filter(s -> !s.settled())
                    .sorted(Comparator.comparing(s -> s.outcome().month())).toList();
        }

        @Override
        public List<Stored> findAll(WorkspaceId workspaceId, String algorithmVersion) {
            return rows.values().stream().sorted(Comparator.comparing(s -> s.outcome().month())).toList();
        }
    }
}
