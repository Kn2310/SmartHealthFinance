package com.smarthealthfinance.forecast.infrastructure.persistence.adapter;

import com.jayway.jsonpath.JsonPath;
import com.smarthealthfinance.forecast.application.port.ForecastReader.SnapshotState;
import com.smarthealthfinance.forecast.application.port.ForecastSnapshotStore;
import com.smarthealthfinance.forecast.application.port.MonthOutcomeStore;
import com.smarthealthfinance.forecast.application.port.MonthOutcomeStore.Stored;
import com.smarthealthfinance.forecast.application.port.RecurringSeriesRepository;
import com.smarthealthfinance.forecast.domain.enums.Confidence;
import com.smarthealthfinance.forecast.domain.enums.Periodicity;
import com.smarthealthfinance.forecast.domain.enums.SeriesDirection;
import com.smarthealthfinance.forecast.domain.enums.SeriesOrigin;
import com.smarthealthfinance.forecast.domain.enums.SeriesStatus;
import com.smarthealthfinance.forecast.domain.model.Channel;
import com.smarthealthfinance.forecast.domain.model.ForecastEntry;
import com.smarthealthfinance.forecast.domain.model.ForecastInput;
import com.smarthealthfinance.forecast.domain.model.ForecastResult;
import com.smarthealthfinance.forecast.domain.model.MonthOutcome;
import com.smarthealthfinance.forecast.domain.model.MonthOutcome.Figures;
import com.smarthealthfinance.forecast.domain.model.Range;
import com.smarthealthfinance.forecast.domain.model.RecurringSeries;
import com.smarthealthfinance.forecast.domain.service.ForecastCalculator;
import com.smarthealthfinance.forecast.domain.service.MonthOutcomes;
import com.smarthealthfinance.identity.IdentityTables;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.support.IntegrationTest;
import com.smarthealthfinance.transactions.domain.enums.CashFlowRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.smarthealthfinance.identity.IdentityFixtures.ISSUER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * Stores do Forecast contra o PostgreSQL (ADR-0031 §3, §4, §6): debounce, {@code SKIP LOCKED}, ida e volta exata do
 * JSONB com dinheiro em string, versões, séries (a detecção nunca escreve o estado do usuário), congelamento
 * ({@code ON CONFLICT DO NOTHING}), reconciliação e imutabilidade, e isolamento por Workspace. Não há job do Forecast
 * nesta fatia; as marcas usam instantes no futuro distante de qualquer forma.
 */
class ForecastStoresIT extends IntegrationTest {

    private static final Instant FUTURE = Instant.parse("2099-01-01T00:00:00Z");
    private static final Duration QUIET = Duration.ofSeconds(10);
    private static final Duration MAX_WAIT = Duration.ofSeconds(60);
    private static final Currency BRL = Currency.getInstance("BRL");
    private static final UUID ACCOUNT = UUID.nameUUIDFromBytes("account".getBytes(StandardCharsets.UTF_8));
    private static final String V1 = ForecastCalculator.ALGORITHM_VERSION;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ForecastSnapshotStore store;

    @Autowired
    RecurringSeriesRepository seriesRepository;

    @Autowired
    MonthOutcomeStore outcomes;

    @Autowired
    PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;
    private WorkspaceId workspace;
    private WorkspaceId other;

    @BeforeEach
    void setUp() throws Exception {
        IdentityTables.clean(jdbc);
        tx = new TransactionTemplate(transactionManager);
        workspace = provision("sub-ana");
        other = provision("sub-bob");
    }

    @AfterEach
    void clearMarks() {
        jdbc.update("delete from forecast_workspaces");
    }

    private WorkspaceId provision(String subject) throws Exception {
        MvcTestResult me = mvc.post().uri("/api/v1/users/me").with(jwt().jwt(token -> token.subject(subject)
                .claim("iss", ISSUER).claim("email", subject + "@example.com").claim("name", "Nome"))).exchange();
        return new WorkspaceId(UUID.fromString(JsonPath.read(
                me.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.workspaceId")));
    }

    // --- marcas e snapshot ---

    @Test
    void eachMarkPostponesTheRecalculationButNeverBeyondTheCap() {
        tx.executeWithoutResult(s -> store.markDirty(workspace, FUTURE, QUIET, MAX_WAIT));
        assertThat(due()).isEqualTo(FUTURE.plus(QUIET));
        assertThat(dirtySince()).isEqualTo(FUTURE);

        tx.executeWithoutResult(s -> store.markDirty(workspace, FUTURE.plusSeconds(30), QUIET, MAX_WAIT));
        assertThat(due()).isEqualTo(FUTURE.plusSeconds(40));

        tx.executeWithoutResult(s -> store.markDirty(workspace, FUTURE.plusSeconds(55), QUIET, MAX_WAIT));
        assertThat(due()).isEqualTo(FUTURE.plusSeconds(60));
        assertThat(dirtySince()).isEqualTo(FUTURE);
    }

    @Test
    void anActionMarksTheWorkspaceDueNowWithoutDebounceAndNeverLaterThanBefore() {
        tx.executeWithoutResult(s -> store.markDirty(workspace, FUTURE, QUIET, MAX_WAIT));
        tx.executeWithoutResult(s -> store.markDueNow(workspace, FUTURE.plusSeconds(3)));

        assertThat(due()).isEqualTo(FUTURE.plusSeconds(3));
        assertThat(dirtySince()).isEqualTo(FUTURE);

        // sem marca anterior nasce vencida agora; um evento depois a adia pelo debounce, como qualquer outro
        tx.executeWithoutResult(s -> store.markDueNow(other, FUTURE));
        tx.executeWithoutResult(s -> store.markDirty(other, FUTURE.plusSeconds(1), QUIET, MAX_WAIT));
        assertThat(jdbc.queryForObject("select due_at from forecast_workspaces where workspace_id = ?",
                Timestamp.class, other.value()).toInstant()).isEqualTo(FUTURE.plusSeconds(11));
    }

    @Test
    void replaceClearsTheMarkKeepsOtherVersionsAndRoundTripsExactly() {
        ForecastResult v1 = result(V1);
        ForecastResult old = withVersion(result(V1), "forecast-v0");
        tx.executeWithoutResult(s -> {
            store.replace(workspace, old, FUTURE);
            store.markDirty(workspace, FUTURE, QUIET, MAX_WAIT);
            store.replace(workspace, v1, FUTURE.plusSeconds(1));
            store.replace(workspace, v1, FUTURE.plusSeconds(2));
        });

        assertThat(jdbc.queryForObject("select count(*) from forecast_snapshots where workspace_id = ?",
                Integer.class, workspace.value())).isEqualTo(2);
        assertThat(dirtySince()).isNull();
        assertThat(due()).isNull();
        // ida e volta exata, inclusive escalas dos decimais, enums, datas e a situação das séries
        assertThat(find(workspace, V1)).contains(v1);
        assertThat(find(workspace, "forecast-v0")).contains(old);
        // dinheiro e razões viram string no JSONB, nunca número
        assertThat(jdbc.queryForObject("""
                select jsonb_typeof(result #> '{month,expense,value,amount}') || ' '
                    || jsonb_typeof(result #> '{month,expense,low,amount}') || ' '
                    || jsonb_typeof(result #> '{series,0,series,expectedAmount,amount}')
                from forecast_snapshots where workspace_id = ? and algorithm_version = 'forecast-v1'
                """, String.class, workspace.value())).isEqualTo("string string string");

        SnapshotState state = read(() -> store.state(workspace, V1));
        assertThat(state.built()).isTrue();
        assertThat(state.dirty()).isFalse();
        assertThat(state.latestAsOf()).isEqualTo(v1.asOf());
        assertThat(state.computedAt()).isEqualTo(FUTURE.plusSeconds(2));
        assertThat(state.hasTransactions()).isFalse();
        assertThat(read(() -> store.state(workspace, "forecast-v9")).built()).isFalse();
    }

    @Test
    void theReaderServesFromTheSnapshotAndNeverAcrossWorkspaces() {
        ForecastResult v1 = result(V1);
        tx.executeWithoutResult(s -> store.replace(workspace, v1, FUTURE));

        assertThat(read(() -> store.cashFlow(workspace))).isEqualTo(Optional.ofNullable(v1.cashFlow()));
        assertThat(read(() -> store.monthlySavingCapacity(workspace)))
                .isEqualTo(Optional.ofNullable(v1.savingCapacity()));
        assertThat(read(() -> store.recurringSeries(workspace))).hasSize(2)
                .allSatisfy(a -> assertThat(a.isEligible()).isTrue());

        assertThat(read(() -> store.current(other))).isEmpty();
        assertThat(read(() -> store.cashFlow(other))).isEmpty();
        assertThat(read(() -> store.recurringSeries(other))).isEmpty();
        assertThat(read(() -> store.state(other, V1)).built()).isFalse();
    }

    @Test
    void dailyJobMarksOnlyWorkspacesWithASnapshot() {
        tx.executeWithoutResult(s -> store.replace(workspace, result(V1), FUTURE));

        int marked = read(() -> store.markAllWithSnapshotsDirty(FUTURE, QUIET, MAX_WAIT));

        assertThat(marked).isEqualTo(1);
        assertThat(dirtySince()).isEqualTo(FUTURE);
        assertThat(jdbc.queryForObject("select count(*) from forecast_workspaces where workspace_id = ?",
                Integer.class, other.value())).isZero();
    }

    /** Duas instâncias nunca recalculam o mesmo Workspace: a segunda não vê o vencido que está bloqueado. */
    @Test
    void claimSkipsWorkspacesLockedByAnotherInstance() throws Exception {
        tx.executeWithoutResult(s -> store.markDirty(workspace, FUTURE, QUIET, MAX_WAIT));
        Instant later = FUTURE.plus(Duration.ofMinutes(5));
        CountDownLatch claimed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<WorkspaceId>> first = pool.submit(() -> read(() -> {
                Optional<WorkspaceId> due = store.claimDue(later);
                claimed.countDown();
                await(release);
                return due;
            }));
            assertThat(claimed.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(read(() -> store.claimDue(later))).isEmpty();
            release.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).contains(workspace);
        }
        finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void postponeKeepsTheMarkAndRebuildTargetsAreWorkspacesWithTransactionsAndNoSnapshot() {
        tx.executeWithoutResult(s -> store.markDirty(workspace, FUTURE, QUIET, MAX_WAIT));
        tx.executeWithoutResult(s -> store.postpone(workspace, FUTURE.plusSeconds(500)));
        assertThat(due()).isEqualTo(FUTURE.plusSeconds(500));
        assertThat(dirtySince()).isEqualTo(FUTURE);
        assertThat(read(() -> store.oldestDirtySince())).contains(FUTURE);

        // sem transações, nenhum Workspace é alvo do rebuild
        assertThat(read(() -> store.workspacesToRebuild(V1, true))).isEmpty();
    }

    // --- séries ---

    @Test
    void redetectionWritesOnlyTheDetectedColumnsAndNeverDeletes() {
        RecurringSeries rent = series("aluguel", SeriesStatus.DETECTED, "1000.00");
        RecurringSeries gym = series("academia", SeriesStatus.DETECTED, "100.00");
        tx.executeWithoutResult(s -> seriesRepository.upsertDetected(workspace, List.of(rent, gym), FUTURE));
        assertThat(findSeries(workspace, "merchant:aluguel")).contains(rent);

        // o usuário confirma e edita
        RecurringSeries edited = rent.confirm(FUTURE.plusSeconds(5))
                .edit(Money.of("1234.00", BRL), 20);
        tx.executeWithoutResult(s -> seriesRepository.saveUserState(workspace, edited, FUTURE.plusSeconds(5)));

        // a redetecção volta com valores novos e (de propósito) um estado DETECTED/USER diferente na entrada
        RecurringSeries redetected = new RecurringSeries(rent.direction(), rent.key(), "ALUGUEL NOVO",
                Channel.card(UUID.randomUUID()), Periodicity.MONTHLY, 8, Money.of("1010.00", BRL),
                new Range(Money.of("990.00", BRL), Money.of("1030.00", BRL)), Confidence.HIGH,
                List.of(UUID.randomUUID(), UUID.randomUUID()), "forecast-v1", SeriesStatus.IGNORED,
                SeriesOrigin.USER, Money.of("1.00", BRL), 3, null);
        tx.executeWithoutResult(s -> seriesRepository.upsertDetected(workspace, List.of(redetected), FUTURE));

        RecurringSeries stored = findSeries(workspace, "merchant:aluguel").orElseThrow();
        assertThat(stored.label()).isEqualTo("ALUGUEL NOVO");
        assertThat(stored.channel()).isEqualTo(redetected.channel());
        assertThat(stored.expectedDay()).isEqualTo(8);
        assertThat(stored.expectedAmount()).isEqualTo(Money.of("1010.00", BRL));
        assertThat(stored.amountRange()).isEqualTo(redetected.amountRange());
        assertThat(stored.confidence()).isEqualTo(Confidence.HIGH);
        assertThat(stored.occurrences()).isEqualTo(redetected.occurrences());
        // estado do usuário intacto
        assertThat(stored.status()).isEqualTo(SeriesStatus.CONFIRMED);
        assertThat(stored.origin()).isEqualTo(SeriesOrigin.DETECTED);
        assertThat(stored.userExpectedAmount()).isEqualTo(Money.of("1234.00", BRL));
        assertThat(stored.userExpectedDay()).isEqualTo(20);
        assertThat(stored.statusChangedAt()).isEqualTo(FUTURE.plusSeconds(5));
        // a série que a redetecção não devolveu continua lá
        assertThat(read(() -> seriesRepository.findAll(workspace))).hasSize(2);
    }

    @Test
    void seriesAreIsolatedByWorkspace() {
        tx.executeWithoutResult(s -> seriesRepository.upsertDetected(workspace,
                List.of(series("aluguel", SeriesStatus.DETECTED, "1000.00")), FUTURE));
        tx.executeWithoutResult(s -> seriesRepository.upsertDetected(other,
                List.of(series("aluguel", SeriesStatus.DETECTED, "700.00")), FUTURE));

        assertThat(read(() -> seriesRepository.findAll(other))).singleElement()
                .extracting(RecurringSeries::expectedAmount).isEqualTo(Money.of("700.00", BRL));
        tx.executeWithoutResult(s -> seriesRepository.saveUserState(other,
                series("aluguel", SeriesStatus.DETECTED, "700.00").ignore(FUTURE), FUTURE));

        assertThat(findSeries(workspace, "merchant:aluguel").orElseThrow().status()).isEqualTo(SeriesStatus.DETECTED);
        assertThat(findSeries(other, "merchant:aluguel").orElseThrow().status()).isEqualTo(SeriesStatus.IGNORED);
        assertThat(read(() -> seriesRepository.findForUpdate(other, SeriesDirection.INCOME, "merchant:aluguel")))
                .isEmpty();
    }

    @Test
    void theSeriesIdIsCreatedOnInsertNeverChangedByRedetectionAndResolvedOnlyInsideTheWorkspace() {
        RecurringSeries rent = series("aluguel", SeriesStatus.DETECTED, "1000.00");
        tx.executeWithoutResult(s -> seriesRepository.upsertDetected(workspace, List.of(rent), FUTURE));
        UUID id = read(() -> seriesRepository.findAllRefs(workspace)).getFirst().id();

        tx.executeWithoutResult(s -> seriesRepository.upsertDetected(workspace,
                List.of(series("aluguel", SeriesStatus.DETECTED, "1100.00")), FUTURE.plusSeconds(1)));

        assertThat(read(() -> seriesRepository.findAllRefs(workspace))).singleElement()
                .satisfies(ref -> {
                    assertThat(ref.id()).isEqualTo(id);
                    assertThat(ref.direction()).isEqualTo(SeriesDirection.EXPENSE);
                    assertThat(ref.key()).isEqualTo("merchant:aluguel");
                });
        assertThat(read(() -> seriesRepository.findRef(workspace, id))).isPresent();
        // o id de um Workspace não resolve em outro
        assertThat(read(() -> seriesRepository.findRef(other, id))).isEmpty();
        assertThat(read(() -> seriesRepository.findAllRefs(other))).isEmpty();
    }

    // --- previsto x realizado ---

    @Test
    void freezeIsOnceReconcilesUntilSettledAndThenIsImmutable() {
        MonthOutcome frozen = MonthOutcomes.freeze(result(V1).month(), V1);
        YearMonth month = frozen.month();

        assertThat(read(() -> outcomes.freeze(workspace, frozen, FUTURE))).isTrue();
        // segundo congelamento (ON CONFLICT DO NOTHING): a primeira projeção vence
        MonthOutcome other = MonthOutcomes.freeze(withFigures(result(V1).month()), V1);
        assertThat(read(() -> outcomes.freeze(workspace, other, FUTURE.plusSeconds(9)))).isFalse();
        Stored first = read(() -> outcomes.find(workspace, month, V1)).orElseThrow();
        assertThat(first.outcome()).isEqualTo(frozen);
        assertThat(first.frozenAt()).isEqualTo(FUTURE);
        assertThat(first.settled()).isFalse();

        // reconciliação: só o realizado e o erro
        Figures realized = new Figures(Money.of("4000.00", BRL), Money.of("7000.00", BRL),
                Money.of("3000.00", BRL));
        MonthOutcome reconciled = MonthOutcomes.reconcile(frozen, realized);
        assertThat(read(() -> outcomes.reconcile(workspace, reconciled))).isTrue();
        Stored afterReconcile = read(() -> outcomes.find(workspace, month, V1)).orElseThrow();
        assertThat(afterReconcile.outcome()).isEqualTo(reconciled);
        assertThat(afterReconcile.outcome().projected()).isEqualTo(frozen.projected());
        assertThat(read(() -> outcomes.findUnsettled(workspace, V1))).hasSize(1);

        // regrava de novo (importação atrasada) e liquida
        Figures later = new Figures(Money.of("4100.00", BRL), Money.of("7000.00", BRL), Money.of("2900.00", BRL));
        assertThat(read(() -> outcomes.reconcile(workspace, MonthOutcomes.reconcile(frozen, later)))).isTrue();
        assertThat(read(() -> outcomes.settle(workspace, month, V1, FUTURE.plusSeconds(100)))).isTrue();
        assertThat(read(() -> outcomes.settle(workspace, month, V1, FUTURE.plusSeconds(200)))).isFalse();

        // imutável depois: nem reconciliação nem novo congelamento mudam a linha
        Stored settled = read(() -> outcomes.find(workspace, month, V1)).orElseThrow();
        assertThat(settled.settledAt()).isEqualTo(FUTURE.plusSeconds(100));
        assertThat(read(() -> outcomes.reconcile(workspace, MonthOutcomes.reconcile(frozen, realized))))
                .isFalse();
        assertThat(read(() -> outcomes.freeze(workspace, other, FUTURE.plusSeconds(300)))).isFalse();
        assertThat(read(() -> outcomes.find(workspace, month, V1))).contains(settled);
        assertThat(settled.outcome().realized()).isEqualTo(later);
        assertThat(read(() -> outcomes.findUnsettled(workspace, V1))).isEmpty();
        assertThat(read(() -> outcomes.findAll(workspace, V1))).containsExactly(settled);
    }

    @Test
    void outcomesAreIsolatedByWorkspaceAndVersion() {
        MonthOutcome frozen = MonthOutcomes.freeze(result(V1).month(), V1);
        tx.executeWithoutResult(s -> outcomes.freeze(workspace, frozen, FUTURE));

        assertThat(read(() -> outcomes.findAll(other, V1))).isEmpty();
        assertThat(read(() -> outcomes.find(other, frozen.month(), V1))).isEmpty();
        assertThat(read(() -> outcomes.findAll(workspace, "forecast-v2"))).isEmpty();
        // outro Workspace congela o mesmo mês sem conflito
        assertThat(read(() -> outcomes.freeze(other, frozen, FUTURE))).isTrue();
    }

    private <T> T read(java.util.function.Supplier<T> work) {
        return tx.execute(status -> work.get());
    }

    // --- apoio ---

    private Optional<ForecastResult> find(WorkspaceId id, String version) {
        return read(() -> store.find(id, version));
    }

    private Optional<RecurringSeries> findSeries(WorkspaceId id, String key) {
        return read(() -> seriesRepository.find(id, SeriesDirection.EXPENSE, key));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private Instant due() {
        return jdbc.queryForObject("select due_at from forecast_workspaces where workspace_id = ?",
                (rs, row) -> rs.getTimestamp("due_at") == null ? null : rs.getTimestamp("due_at").toInstant(),
                workspace.value());
    }

    private Instant dirtySince() {
        return jdbc.queryForObject("select dirty_since from forecast_workspaces where workspace_id = ?",
                (rs, row) -> rs.getTimestamp("dirty_since") == null ? null
                        : rs.getTimestamp("dirty_since").toInstant(), workspace.value());
    }

    private static RecurringSeries series(String merchant, SeriesStatus status, String amount) {
        return new RecurringSeries(SeriesDirection.EXPENSE, "merchant:" + merchant, merchant.toUpperCase(),
                Channel.account(ACCOUNT), Periodicity.MONTHLY, 5, Money.of(amount, BRL),
                new Range(Money.of(amount, BRL), Money.of(amount, BRL)), Confidence.HIGH,
                List.of(UUID.nameUUIDFromBytes(merchant.getBytes(StandardCharsets.UTF_8))), "forecast-v1", status,
                SeriesOrigin.DETECTED, null, null, null);
    }

    private static ForecastEntry entry(String day, CashFlowRole role, String merchant, String amount) {
        return new ForecastEntry(UUID.nameUUIDFromBytes((day + merchant + amount).getBytes(StandardCharsets.UTF_8)),
                LocalDate.parse(day), role, "CAT", merchant, merchant.toUpperCase(), Money.of(amount, BRL),
                Channel.account(ACCOUNT), false);
    }

    /** Um resultado completo (séries elegíveis, projeção do mês, fluxo de caixa e capacidade) em 20/06/2026. */
    private static ForecastResult result(String version) {
        List<ForecastEntry> entries = new ArrayList<>();
        for (String month : List.of("2026-03", "2026-04", "2026-05", "2026-06")) {
            entries.add(entry(month + "-05", CashFlowRole.INCOME, "empresa", "5000.00"));
            entries.add(entry(month + "-15", CashFlowRole.EXPENSE, "aluguel", "1000.00"));
            entries.add(entry(month + "-08", CashFlowRole.EXPENSE, "mercado", "150.55"));
            entries.add(entry(month + "-20", CashFlowRole.EXPENSE, "mercado", "149.45"));
        }
        ForecastResult result = ForecastCalculator.calculate(new ForecastInput(LocalDate.parse("2026-06-20"), BRL,
                Optional.of(LocalDate.parse("2026-03-05")), entries, List.of(), Money.of("10000.00", BRL), List.of(),
                List.of()));
        assertThat(result.cashFlow()).isNotNull();
        assertThat(result.savingCapacity()).isNotNull();
        return version.equals(result.algorithmVersion()) ? result : withVersion(result, version);
    }

    private static ForecastResult withVersion(ForecastResult r, String version) {
        return new ForecastResult(version, r.asOf(), r.historyMonths(), r.limitations(), r.series(), r.month(),
                r.nextIncome(), r.upcomingEvents(), r.invoices(), r.cashFlow(), r.savingCapacity());
    }

    /** Outra projeção do mesmo mês, para provar que a segunda gravação do congelamento não vence. */
    private static com.smarthealthfinance.forecast.domain.model.MonthProjection withFigures(
            com.smarthealthfinance.forecast.domain.model.MonthProjection m) {
        return new com.smarthealthfinance.forecast.domain.model.MonthProjection(m.month(), m.asOf(), m.baseMonths(),
                m.expense().plus(com.smarthealthfinance.forecast.domain.model.Projected.fact(
                        Money.of("1.00", BRL))), m.income(), m.net(), m.pending());
    }

}
