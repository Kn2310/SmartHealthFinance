package com.smarthealthfinance.forecast.application;

import com.smarthealthfinance.accounts.domain.model.Account;
import com.smarthealthfinance.accounts.domain.repository.AccountRepository;
import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.forecast.application.port.ForecastSnapshotStore;
import com.smarthealthfinance.forecast.application.port.MonthOutcomeStore;
import com.smarthealthfinance.forecast.application.port.RecurringSeriesRepository;
import com.smarthealthfinance.forecast.application.service.ForecastInputLoader;
import com.smarthealthfinance.forecast.application.usecase.ProcessDueForecastSnapshots;
import com.smarthealthfinance.forecast.application.usecase.RebuildForecastSnapshots;
import com.smarthealthfinance.forecast.domain.enums.OutcomePhase;
import com.smarthealthfinance.forecast.domain.enums.SeriesDirection;
import com.smarthealthfinance.forecast.domain.enums.SeriesStatus;
import com.smarthealthfinance.forecast.domain.model.ForecastResult;
import com.smarthealthfinance.forecast.domain.model.MonthProjection;
import com.smarthealthfinance.forecast.domain.model.RecurringSeries;
import com.smarthealthfinance.forecast.domain.model.SeriesAssessment;
import com.smarthealthfinance.forecast.domain.service.ForecastCalculator;
import com.smarthealthfinance.forecast.domain.service.MonthOutcomes;
import com.smarthealthfinance.identity.IdentityTables;
import com.smarthealthfinance.identity.domain.model.User;
import com.smarthealthfinance.identity.domain.model.Workspace;
import com.smarthealthfinance.identity.domain.repository.UserRepository;
import com.smarthealthfinance.identity.domain.repository.WorkspaceRepository;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.support.IntegrationTest;
import com.smarthealthfinance.transactions.domain.enums.TransactionStatus;
import com.smarthealthfinance.transactions.domain.enums.TransactionType;
import com.smarthealthfinance.transactions.domain.model.Transaction;
import com.smarthealthfinance.transactions.domain.repository.TransactionRepository;
import com.smarthealthfinance.transactions.domain.valueobject.CardReference;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionDescription;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

import static com.smarthealthfinance.accounts.AccountsFixtures.account;
import static com.smarthealthfinance.identity.IdentityFixtures.CREATED_AT;
import static com.smarthealthfinance.identity.IdentityFixtures.activeUser;
import static com.smarthealthfinance.identity.IdentityFixtures.personalWorkspace;
import static com.smarthealthfinance.transactions.TransactionsFixtures.create;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Paridade do snapshot (ADR-0031 §10): o que o recálculo grava é o {@link ForecastCalculator} rodado direto sobre a
 * entrada das portas (Analytics por transação, Overview, cartões e contas) para o mesmo fixture, sem SQL com semântica
 * financeira nova. Também cobre, contra o PostgreSQL, o estado do usuário que nenhum rebuild apaga, o job de
 * vencidos, o congelamento da virada do mês e o isolamento por Workspace.
 */
class ForecastSnapshotParityIT extends IntegrationTest {

    private static final Currency BRL = Currency.getInstance("BRL");
    private static final String V1 = ForecastCalculator.ALGORITHM_VERSION;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ForecastInputLoader loader;

    @Autowired
    ForecastSnapshotStore store;

    @Autowired
    RecurringSeriesRepository seriesRepository;

    @Autowired
    MonthOutcomeStore outcomes;

    @Autowired
    com.smarthealthfinance.analytics.application.usecase.RebuildAnalyticsProjection analyticsRebuild;

    @Autowired
    RebuildForecastSnapshots rebuild;

    @Autowired
    ProcessDueForecastSnapshots processDue;

    @Autowired
    TransactionRepository transactions;

    @Autowired
    AccountRepository accounts;

    @Autowired
    UserRepository users;

    @Autowired
    WorkspaceRepository workspaces;

    @Autowired
    PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;
    private LocalDate today;
    private WorkspaceId anas;
    private WorkspaceId bobs;
    /** Despesas semeadas de Ana por mês (conta e cartão), para conferir o realizado. */
    private final List<BigDecimal> currentMonthExpenses = new ArrayList<>();
    private BigDecimal previousMonthExpenses = BigDecimal.ZERO;

    @BeforeEach
    void setUp() {
        IdentityTables.clean(jdbc);
        tx = new TransactionTemplate(transactionManager);
        today = loader.today();
        anas = persistedWorkspace(activeUser("sub-ana"));
        bobs = persistedWorkspace(activeUser("sub-bob"));
        seed(anas, true);
        seed(bobs, false);
        // firstActivity vem da projeção do Analytics: constrói a dos dois Workspaces como o rebuild do deploy
        analyticsRebuild.execute(anas);
        analyticsRebuild.execute(bobs);
        // O rebuild do Analytics publica analytics.projection-updated, que o consumidor e o job do Forecast tratam em
        // segundo plano. Espera isso assentar e parte de um Forecast vazio, para que nada assíncrono marque ou grave
        // durante o teste.
        IdentityTables.awaitQuiescence(jdbc);
        jdbc.update("delete from forecast_month_outcomes");
        jdbc.update("delete from forecast_recurring_series");
        jdbc.update("delete from forecast_snapshots");
        jdbc.update("delete from forecast_workspaces");
        jdbc.update("delete from outbox_events where event_type = 'forecast.snapshot-updated'");
    }

    @AfterEach
    void clearMarks() {
        jdbc.update("delete from forecast_workspaces");
    }

    @Test
    void theStoredSnapshotIsTheDomainFunctionOverThePorts() {
        rebuild.execute(anas);

        ForecastResult stored = read(() -> store.find(anas, V1)).orElseThrow();
        ForecastResult expected = read(() -> ForecastCalculator.calculate(loader.load(anas)));

        assertThat(stored).isEqualTo(expected);
        assertThat(stored.asOf()).isEqualTo(today);
        // histórico suficiente (primeiro lançamento 5 meses atrás): todas as projeções existem
        assertThat(stored.limitations()).doesNotContain(com.smarthealthfinance.forecast.domain.enums.Limitation.INSUFFICIENT_HISTORY);
        assertThat(stored.cashFlow()).isNotNull();
        assertThat(stored.savingCapacity()).isNotNull();
        assertThat(stored.series()).extracting(a -> a.series().key()).anyMatch(k -> k.contains("imobiliaria"))
                .anyMatch(k -> k.contains("empresa"));
        assertThat(stored.invoices()).isNotEmpty();
        // o realizado do mês vem do fluxo por transação (conta + cartão), que é o das portas
        BigDecimal realized = currentMonthExpenses.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(stored.month().expense().factPart()).isEqualTo(Money.of(realized.toPlainString(), BRL));
        // a série persistida é a do snapshot, com o estado de detecção
        List<RecurringSeries> persisted = read(() -> seriesRepository.findAll(anas));
        assertThat(persisted).containsExactlyInAnyOrderElementsOf(
                stored.series().stream().map(SeriesAssessment::series).toList());
        // evento de integração: só a versão
        assertThat(jdbc.queryForList("""
                select payload::text from outbox_events
                where event_type = 'forecast.snapshot-updated' and workspace_id = ?
                """, String.class, anas.value())).singleElement().satisfies(payload ->
                assertThat(payload.replace(" ", "")).isEqualTo("{\"algorithmVersion\":\"forecast-v1\"}"));
    }

    @Test
    void rebuildNeverChangesTheUserStateNorDeletesSeries() {
        rebuild.execute(anas);
        RecurringSeries rent = read(() -> seriesRepository.findAll(anas)).stream()
                .filter(series -> series.key().contains("imobiliaria")).findFirst().orElseThrow();
        RecurringSeries salary = read(() -> seriesRepository.findAll(anas)).stream()
                .filter(series -> series.key().contains("empresa")).findFirst().orElseThrow();
        Instant at = Instant.parse("2026-10-01T10:00:00Z");
        RecurringSeries edited = rent.confirm(at).edit(Money.of("1999.99", BRL), 28);
        tx.executeWithoutResult(s -> {
            seriesRepository.saveUserState(anas, edited, at);
            seriesRepository.saveUserState(anas, salary.ignore(at), at);
        });
        int before = jdbc.queryForObject("select count(*) from forecast_recurring_series where workspace_id = ?",
                Integer.class, anas.value());

        rebuild.execute(anas);
        rebuild.execute(RebuildForecastSnapshots.Scope.ALL);

        RecurringSeries storedRent = read(() -> seriesRepository.find(anas, rent.direction(), rent.key()))
                .orElseThrow();
        assertThat(storedRent.status()).isEqualTo(SeriesStatus.CONFIRMED);
        assertThat(storedRent.userExpectedAmount()).isEqualTo(Money.of("1999.99", BRL));
        assertThat(storedRent.userExpectedDay()).isEqualTo(28);
        assertThat(storedRent.statusChangedAt()).isEqualTo(at);
        assertThat(storedRent.expectedAmount()).isEqualTo(rent.expectedAmount());
        assertThat(read(() -> seriesRepository.find(anas, salary.direction(), salary.key())).orElseThrow()
                .status()).isEqualTo(SeriesStatus.IGNORED);
        assertThat(jdbc.queryForObject("select count(*) from forecast_recurring_series where workspace_id = ?",
                Integer.class, anas.value())).isEqualTo(before);

        // o snapshot gravado já reflete o estado do usuário
        ForecastResult stored = read(() -> store.find(anas, V1)).orElseThrow();
        assertThat(stored.series()).filteredOn(a -> a.series().key().equals(rent.key())).singleElement()
                .satisfies(a -> assertThat(a.series().effectiveAmount()).isEqualTo(Money.of("1999.99", BRL)));
        assertThat(stored.series()).filteredOn(a -> a.series().key().equals(salary.key())).singleElement()
                .satisfies(a -> assertThat(a.isEligible()).isFalse());
    }

    @Test
    void missingScopeRebuildsOnlyWorkspacesWithoutASnapshot() {
        assertThat(rebuild.execute(RebuildForecastSnapshots.Scope.MISSING)).isEqualTo(2);
        assertThat(rebuild.execute(RebuildForecastSnapshots.Scope.MISSING)).isZero();
        assertThat(rebuild.execute(RebuildForecastSnapshots.Scope.ALL)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from forecast_snapshots", Integer.class)).isEqualTo(2);
    }

    @Test
    void workspacesNeverMix() {
        rebuild.execute(anas);
        rebuild.execute(bobs);

        ForecastResult ana = read(() -> store.find(anas, V1)).orElseThrow();
        ForecastResult bob = read(() -> store.find(bobs, V1)).orElseThrow();

        assertThat(ana).isNotEqualTo(bob);
        assertThat(bob.series()).extracting(a -> a.series().key()).noneMatch(k -> k.contains("imobiliaria"));
        assertThat(bob.invoices()).isEmpty();
        assertThat(read(() -> seriesRepository.findAll(bobs))).extracting(RecurringSeries::key)
                .noneMatch(k -> k.contains("imobiliaria"));
        assertThat(read(() -> seriesRepository.find(bobs, SeriesDirection.EXPENSE,
                tx.execute(t -> seriesRepository.findAll(anas)).stream().filter(x -> x.key().contains("imobiliaria"))
                        .findFirst().orElseThrow().key()))).isEmpty();
        assertThat(read(() -> loader.load(bobs)).entries()).allSatisfy(entry ->
                assertThat(entry.merchantKey()).isNotEqualTo("imobiliaria central"));
    }

    @Test
    void theJobPicksTheDueWorkspaceRecalculatesAndClearsTheMark() {
        tx.executeWithoutResult(s -> store.markDueNow(anas, Instant.now().minusSeconds(5)));
        assertThat(read(() -> store.state(anas, V1)).built()).isFalse();

        assertThat(processDue.execute()).isEqualTo(1);

        ForecastSnapshotStore.SnapshotState state = read(() -> store.state(anas, V1));
        assertThat(state.built()).isTrue();
        assertThat(state.dirty()).isFalse();
        assertThat(state.latestAsOf()).isEqualTo(today);
        assertThat(state.hasTransactions()).isTrue();
        assertThat(processDue.execute()).isZero();
    }

    @Test
    void theMonthThatClosedIsFrozenOnceAndReconciledAgainstTheRealizedFlow() {
        rebuild.execute(anas);
        ForecastResult current = read(() -> store.find(anas, V1)).orElseThrow();
        YearMonth previous = YearMonth.from(today).minusMonths(1);
        // o snapshot que existia na virada: uma projeção de ontem-do-mês-anterior
        MonthProjection june = new MonthProjection(previous, previous.atEndOfMonth(), current.month().baseMonths(),
                current.month().expense(), current.month().income(), current.month().net(), List.of());
        ForecastResult atTurn = new ForecastResult(V1, previous.atEndOfMonth(), current.historyMonths(),
                current.limitations(), current.series(), june, current.nextIncome(), current.upcomingEvents(),
                current.invoices(), current.cashFlow(), current.savingCapacity());
        tx.executeWithoutResult(s -> store.replace(anas, atTurn, Instant.now()));

        rebuild.execute(anas);

        var stored = read(() -> outcomes.find(anas, previous, V1)).orElseThrow();
        assertThat(stored.outcome().projected().expense()).isEqualTo(current.month().expense().value());
        assertThat(stored.outcome().realized().expense())
                .isEqualTo(Money.of(previousMonthExpenses.toPlainString(), BRL));
        assertThat(stored.settled()).isEqualTo(MonthOutcomes.phase(previous, today) == OutcomePhase.SETTLED);

        // o realizado de um mês liquidado não muda mais; um recálculo com o mesmo mês não congela de novo
        rebuild.execute(anas);
        assertThat(read(() -> outcomes.findAll(anas, V1))).hasSize(1);
        assertThat(read(() -> outcomes.find(anas, previous, V1)).orElseThrow().outcome().projected())
                .isEqualTo(stored.outcome().projected());
    }

    private <T> T read(java.util.function.Supplier<T> work) {
        return tx.execute(status -> work.get());
    }

    // --- dados ---

    /**
     * Cinco meses fechados e o mês corrente até hoje: salário no dia 5, aluguel no dia 15 e mercado variável; Ana
     * tem também um cartão com compras. Registra as despesas do mês corrente e do anterior para conferir o realizado.
     */
    private void seed(WorkspaceId workspace, boolean withCardAndRent) {
        AccountId id = persistedAccount(account(workspace, "Conta " + workspace.value()));
        UUID card = withCardAndRent ? persistedCard(workspace) : null;
        List<Transaction> all = new ArrayList<>();
        YearMonth current = YearMonth.from(today);
        for (int back = 5; back >= 0; back--) {
            YearMonth month = current.minusMonths(back);
            add(all, workspace, id, TransactionType.INCOME, month, 5, "6500.00", "Empresa Fictícia Ltda", back);
            if (withCardAndRent) {
                add(all, workspace, id, TransactionType.EXPENSE, month, 15, "1800.00", "Imobiliária Central", back);
            }
            add(all, workspace, id, TransactionType.EXPENSE, month, 8, "%d.50".formatted(120 + back * 7),
                    "Mercado Bom Preço", back);
            add(all, workspace, id, TransactionType.EXPENSE, month, 21, "%d.20".formatted(90 + back * 3),
                    "Mercado Bom Preço", back);
            if (card != null) {
                LocalDate day = month.atDay(Math.min(12, month.lengthOfMonth()));
                if (!day.isAfter(today)) {
                    String amount = "%d.80".formatted(300 + back * 11);
                    all.add(Transaction.createOnCard(TransactionId.generate(CREATED_AT), workspace,
                            TransactionType.EXPENSE, new CardReference(card), Money.of(amount, BRL), day,
                            new TransactionDescription("Restaurante Sol"), null, CREATED_AT));
                    record(workspace, back, amount);
                }
            }
        }
        tx.executeWithoutResult(s -> transactions.addAll(all));
    }

    private void add(List<Transaction> all, WorkspaceId workspace, AccountId account, TransactionType type,
                     YearMonth month, int day, String amount, String description, int back) {
        LocalDate date = month.atDay(Math.min(day, month.lengthOfMonth()));
        if (date.isAfter(today)) {
            return;
        }
        all.add(create(workspace, type, account, null, null, amount, date, description, TransactionStatus.POSTED,
                null, CREATED_AT));
        if (type == TransactionType.EXPENSE) {
            record(workspace, back, amount);
        }
    }

    private void record(WorkspaceId workspace, int back, String amount) {
        if (!workspace.equals(anas)) {
            return;
        }
        if (back == 0) {
            currentMonthExpenses.add(new BigDecimal(amount));
        }
        else if (back == 1) {
            previousMonthExpenses = previousMonthExpenses.add(new BigDecimal(amount));
        }
    }

    private UUID persistedCard(WorkspaceId workspace) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into credit_cards (id, workspace_id, nickname, brand, last4, credit_limit, currency, closing_day,
                  due_day, status, tracking_start_month, created_at, updated_at)
                values (?, ?, 'Cartão', 'VISA', '4821', 10000, 'BRL', 28, 5, 'ACTIVE', date '2000-01-01', now(), now())""",
                id, workspace.value());
        return id;
    }

    private WorkspaceId persistedWorkspace(User owner) {
        Workspace workspace = personalWorkspace(owner);
        tx.executeWithoutResult(s -> {
            users.addIfAbsent(owner);
            workspaces.addPersonalIfAbsent(workspace);
        });
        return workspace.id();
    }

    private AccountId persistedAccount(Account account) {
        accounts.add(account);
        return account.id();
    }
}
