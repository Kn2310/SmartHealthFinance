package com.smarthealthfinance.overview.infrastructure.persistence.adapter;

import com.smarthealthfinance.accounts.domain.enums.AccountType;
import com.smarthealthfinance.accounts.domain.model.Account;
import com.smarthealthfinance.accounts.domain.repository.AccountRepository;
import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.accounts.domain.valueobject.AccountName;
import com.smarthealthfinance.identity.IdentityTables;
import com.smarthealthfinance.identity.domain.model.User;
import com.smarthealthfinance.identity.domain.model.Workspace;
import com.smarthealthfinance.identity.domain.repository.UserRepository;
import com.smarthealthfinance.identity.domain.repository.WorkspaceRepository;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.overview.application.ReferenceOverviewReadModel;
import com.smarthealthfinance.overview.application.port.OverviewReadModel;
import com.smarthealthfinance.overview.application.port.OverviewReadModel.AccountFigures;
import com.smarthealthfinance.overview.application.port.OverviewReadModel.Activity;
import com.smarthealthfinance.overview.domain.enums.PeriodType;
import com.smarthealthfinance.overview.domain.model.CashFlow;
import com.smarthealthfinance.overview.domain.valueobject.OverviewPeriod;
import com.smarthealthfinance.support.IntegrationTest;
import com.smarthealthfinance.transactions.domain.enums.AdjustmentDirection;
import com.smarthealthfinance.transactions.domain.enums.TransactionStatus;
import com.smarthealthfinance.transactions.domain.enums.TransactionType;
import com.smarthealthfinance.transactions.domain.model.Transaction;
import com.smarthealthfinance.transactions.domain.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.smarthealthfinance.accounts.AccountsFixtures.account;
import static com.smarthealthfinance.identity.IdentityFixtures.CREATED_AT;
import static com.smarthealthfinance.identity.IdentityFixtures.activeUser;
import static com.smarthealthfinance.identity.IdentityFixtures.personalWorkspace;
import static com.smarthealthfinance.transactions.TransactionsFixtures.brl;
import static com.smarthealthfinance.transactions.TransactionsFixtures.create;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * PostgreSQL real. O núcleo é o teste de paridade: o SQL agregado precisa dar o mesmo resultado que a implementação
 * de referência, que soma {@code Transaction.balanceEffectOn} em memória (ADR-0006).
 */
class JdbcOverviewReadModelIT extends IntegrationTest {

    private static final java.util.Currency BRL = Workspace.DEFAULT_BASE_CURRENCY;

    @Autowired
    OverviewReadModel readModel;

    @Autowired
    TransactionRepository transactions;

    @Autowired
    AccountRepository accounts;

    @Autowired
    UserRepository users;

    @Autowired
    WorkspaceRepository workspaces;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    private ReferenceOverviewReadModel reference;
    private WorkspaceId anas;
    private WorkspaceId bobs;
    private AccountId aurora;
    private AccountId norte;
    private AccountId brokerage;
    private AccountId archived;
    private AccountId bobsAccount;

    @BeforeEach
    void setUp() {
        IdentityTables.clean(jdbc);
        reference = new ReferenceOverviewReadModel(transactions, accounts);
        anas = persistedWorkspace(activeUser("sub-ana"));
        bobs = persistedWorkspace(activeUser("sub-bob"));
        aurora = persistedAccount(anas, "Banco Aurora", true);
        norte = persistedAccount(anas, "Banco Norte", true);
        brokerage = persistedAccount(anas, "Corretora", false);
        archived = persistedAccount(anas, "Conta antiga", true);
        archive(anas, archived);
        bobsAccount = persistedAccount(bobs, "Conta do Bob", true);
    }

    // --- paridade com a semântica do domínio ---

    @Test
    void sqlMatchesTheDomainSemanticsForEveryTypeAndStatus() {
        populateEverything();

        for (OverviewPeriod period : periods()) {
            assertParity(anas, period);
        }
    }

    @Test
    void sqlMatchesTheDomainSemanticsWithoutAnyTransaction() {
        assertParity(anas, period("2026-09-01", "2026-09-27"));
        assertThat(readModel.accountFigures(anas, period("2026-09-01", "2026-09-27"), BRL)).isEmpty();
        assertThat(readModel.activity(anas, period("2026-09-01", "2026-09-27"), BRL).hasPostedTransactions()).isFalse();
    }

    // --- números conhecidos ---

    @Test
    void balancesAndCashFlowForAKnownScenario() {
        populateEverything();
        OverviewPeriod september = period("2026-09-01", "2026-09-27");

        Map<AccountId, AccountFigures> figures = readModel.accountFigures(anas, september, BRL);
        CashFlow flow = readModel.cashFlow(anas, september, BRL);
        Activity activity = readModel.activity(anas, september, BRL);

        // aurora: +1000 (ajuste) +5000 -200 +50 (refund) -400 (transfer) -100 (ajuste) +300 (agosto) +1 (2025) = 5651
        assertThat(figures.get(aurora).balance()).isEqualTo(brl("5651"));
        // norte: +400 (transfer recebida) -120
        assertThat(figures.get(norte).balance()).isEqualTo(brl("280"));
        assertThat(figures.get(brokerage).balance()).isEqualTo(brl("700"));
        // conta arquivada ainda tem saldo derivado (a Application decide não exibi-la)
        assertThat(figures.get(archived).balance()).isEqualTo(brl("90"));
        // só aurora e norte: brokerage (fora do total) e arquivada não entram no fluxo
        assertThat(flow.income()).isEqualTo(brl("5000"));
        assertThat(flow.expense()).isEqualTo(brl("320"));
        assertThat(flow.refunds()).isEqualTo(brl("50"));
        assertThat(flow.net()).isEqualTo(brl("4730"));
        assertThat(activity.hasPostedTransactions()).isTrue();
        assertThat(activity.transferCount()).isEqualTo(1);
        assertThat(activity.transferVolume()).isEqualTo(brl("400"));
        assertThat(activity.pendingInPeriod()).isEqualTo(1);
    }

    @Test
    void movementCountsOnlyPostedInsideThePeriodAndBothTransferLegs() {
        populateEverything();

        Map<AccountId, AccountFigures> figures = readModel.accountFigures(anas, period("2026-09-01", "2026-09-27"), BRL);

        // aurora (setembro): ajuste+, income, expense, refund, transfer(origem), ajuste-  = 6 (o de agosto fica fora)
        assertThat(figures.get(aurora).movementCount()).isEqualTo(6);
        // norte: transfer(destino) + expense = 2
        assertThat(figures.get(norte).movementCount()).isEqualTo(2);
    }

    @Test
    void sumsAreExactDecimals() {
        for (int i = 0; i < 300; i++) {
            transactions.add(tx(anas, TransactionType.INCOME, aurora, "0.10", "2026-09-05", TransactionStatus.POSTED));
            transactions.add(tx(anas, TransactionType.EXPENSE, aurora, "0.30", "2026-09-06", TransactionStatus.POSTED));
        }

        OverviewPeriod september = period("2026-09-01", "2026-09-27");

        assertThat(readModel.cashFlow(anas, september, BRL).income()).isEqualTo(brl("30.00"));
        assertThat(readModel.cashFlow(anas, september, BRL).expense()).isEqualTo(brl("90.00"));
        assertThat(readModel.accountFigures(anas, september, BRL).get(aurora).balance()).isEqualTo(brl("-60.00"));
    }

    @Test
    void recentMovementsAreNewestFirstLimitedAndIncludePendingButNotVoided() {
        populateEverything();

        var recent = readModel.recentMovements(anas, period("2026-09-01", "2026-09-27"), 3, BRL);

        assertThat(recent).hasSize(3);
        assertThat(recent).extracting(m -> m.occurredOn()).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(readModel.recentMovements(anas, period("2026-09-01", "2026-09-27"), 50, BRL))
                .extracting(m -> m.status())
                .containsOnly(TransactionStatus.POSTED, TransactionStatus.PENDING);
    }

    // --- isolamento ---

    @Test
    void workspaceQueriesNeverSeeAnotherWorkspace() {
        populateEverything();
        transactions.add(tx(bobs, TransactionType.INCOME, bobsAccount, "42.00", "2026-09-05", TransactionStatus.POSTED));
        OverviewPeriod september = period("2026-09-01", "2026-09-27");

        Map<AccountId, AccountFigures> bobsFigures = readModel.accountFigures(bobs, september, BRL);
        CashFlow bobsFlow = readModel.cashFlow(bobs, september, BRL);

        assertThat(bobsFigures).containsOnlyKeys(bobsAccount);
        assertThat(bobsFigures.get(bobsAccount).balance()).isEqualTo(brl("42"));
        assertThat(bobsFlow.income()).isEqualTo(brl("42"));
        assertThat(bobsFlow.expense()).isEqualTo(brl("0"));
        assertThat(readModel.activity(bobs, september, BRL).postedInPeriod()).isEqualTo(1);
        assertThat(readModel.recentMovements(bobs, september, 20, BRL)).extracting(m -> m.accountId())
                .containsOnly(bobsAccount);

        Map<AccountId, AccountFigures> anasFigures = readModel.accountFigures(anas, september, BRL);
        assertThat(anasFigures).doesNotContainKey(bobsAccount);
        assertThat(readModel.cashFlow(anas, september, BRL).income()).isEqualTo(brl("5000"));
    }

    @Test
    void aWorkspaceWithNoDataIsEmptyEvenWhenOthersHaveData() {
        populateEverything();
        WorkspaceId carol = persistedWorkspace(activeUser("sub-carol"));
        OverviewPeriod september = period("2026-09-01", "2026-09-27");

        assertThat(readModel.accountFigures(carol, september, BRL)).isEmpty();
        assertThat(readModel.cashFlow(carol, september, BRL).net()).isEqualTo(brl("0"));
        assertThat(readModel.activity(carol, september, BRL).hasPostedTransactions()).isFalse();
        assertThat(readModel.recentMovements(carol, september, 20, BRL)).isEmpty();
    }

    // --- índice ---

    @Test
    void partialCoveringIndexExists() {
        String definition = jdbc.queryForObject(
                "select indexdef from pg_indexes where indexname = 'ix_transactions_posted_overview'", String.class);

        assertThat(definition).contains("workspace_id", "occurred_on", "INCLUDE", "status")
                .containsIgnoringCase("POSTED");
    }

    @Test
    void periodQueriesUseTheOverviewIndexOnLargeTables() {
        jdbc.update("""
                insert into transactions (id, workspace_id, account_id, type, amount, currency, occurred_on,
                                          description, status, source, created_at, updated_at)
                select gen_random_uuid(), ?, ?, 'EXPENSE', 1.00, 'BRL', date '2025-01-01' + (g % 365), 'carga',
                       'POSTED', 'MANUAL', now(), now()
                from generate_series(1, 40000) g
                """, anas.value(), aurora.value());
        jdbc.execute("vacuum analyze transactions");

        String plan = new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.execute("set local enable_seqscan = off");
            return String.join("\n", jdbc.queryForList("""
                    explain select coalesce(sum(t.amount) filter (where t.type = 'EXPENSE'), 0)
                    from transactions t
                    where t.workspace_id = '%s' and t.status = 'POSTED' and t.type in ('INCOME', 'EXPENSE', 'REFUND')
                      and t.occurred_on between date '2025-03-01' and date '2025-03-31'
                    """.formatted(anas.value()), String.class));
        });

        assertThat(plan).contains("ix_transactions_posted_overview");
    }

    // --- helpers ---

    /**
     * Cobre todos os tipos, todos os status, contas incluídas/excluídas/arquivada, datas dentro e fora do período
     * (agosto, limites do mês, depois de "hoje").
     */
    private void populateEverything() {
        // Agosto (fora do período de setembro, mas dentro do saldo)
        add(TransactionType.INCOME, aurora, null, null, "300", "2026-08-15", TransactionStatus.POSTED, null);
        add(TransactionType.INCOME, brokerage, null, null, "100", "2026-08-31", TransactionStatus.POSTED, null);

        // Setembro
        add(TransactionType.ADJUSTMENT, aurora, null, AdjustmentDirection.INCREASE, "1000", "2026-09-01",
                TransactionStatus.POSTED, null);
        add(TransactionType.INCOME, aurora, null, null, "5000", "2026-09-05", TransactionStatus.POSTED, null);
        Transaction purchase = add(TransactionType.EXPENSE, aurora, null, null, "200", "2026-09-10",
                TransactionStatus.POSTED, null);
        add(TransactionType.REFUND, aurora, null, null, "50", "2026-09-12", TransactionStatus.POSTED, purchase);
        add(TransactionType.TRANSFER, aurora, norte, null, "400", "2026-09-15", TransactionStatus.POSTED, null);
        add(TransactionType.ADJUSTMENT, aurora, null, AdjustmentDirection.DECREASE, "100", "2026-09-20",
                TransactionStatus.POSTED, null);
        add(TransactionType.EXPENSE, norte, null, null, "120", "2026-09-27", TransactionStatus.POSTED, null);
        add(TransactionType.INCOME, brokerage, null, null, "600", "2026-09-03", TransactionStatus.POSTED, null);
        add(TransactionType.INCOME, archived, null, null, "90", "2026-09-04", TransactionStatus.POSTED, null);

        // Nunca entram no saldo nem no fluxo
        add(TransactionType.EXPENSE, aurora, null, null, "77", "2026-09-11", TransactionStatus.PENDING, null);
        Transaction cancelled = add(TransactionType.EXPENSE, aurora, null, null, "88", "2026-09-13",
                TransactionStatus.PENDING, null);
        change(cancelled, Transaction::cancel);
        Transaction reversed = add(TransactionType.INCOME, aurora, null, null, "99", "2026-09-14",
                TransactionStatus.POSTED, null);
        change(reversed, Transaction::reverse);

        // Depois de "hoje" e depois do período
        add(TransactionType.INCOME, aurora, null, null, "1234", "2026-09-30", TransactionStatus.POSTED, null);
        add(TransactionType.EXPENSE, aurora, null, null, "55", "2026-10-02", TransactionStatus.POSTED, null);

        // Setembro do ano anterior
        add(TransactionType.INCOME, aurora, null, null, "1", "2025-09-10", TransactionStatus.POSTED, null);
    }

    private List<OverviewPeriod> periods() {
        LocalDate today = LocalDate.parse("2026-09-27");
        return List.of(
                OverviewPeriod.resolve(PeriodType.CURRENT_MONTH, null, null, today),
                OverviewPeriod.resolve(PeriodType.PREVIOUS_MONTH, null, null, today),
                period("2026-09-15", "2026-09-15"),
                period("2026-08-31", "2026-09-01"),
                period("2025-01-01", "2025-12-31"),
                period("2026-01-01", "2026-12-31"),
                period("2030-01-01", "2030-01-31"));
    }

    private void assertParity(WorkspaceId workspace, OverviewPeriod period) {
        String context = period.from() + ".." + period.to();

        assertThat(readModel.accountFigures(workspace, period, BRL)).as("figures " + context)
                .isEqualTo(reference.accountFigures(workspace, period, BRL));
        assertThat(readModel.cashFlow(workspace, period, BRL)).as("cashFlow " + context)
                .isEqualTo(reference.cashFlow(workspace, period, BRL));
        assertThat(readModel.activity(workspace, period, BRL)).as("activity " + context)
                .isEqualTo(reference.activity(workspace, period, BRL));
        assertThat(readModel.recentMovements(workspace, period, 7, BRL)).as("recent " + context)
                .isEqualTo(reference.recentMovements(workspace, period, 7, BRL));
        assertThat(readModel.recentMovements(workspace, period, 100, BRL).stream()
                .collect(Collectors.counting())).as("recent size " + context)
                .isEqualTo((long) reference.recentMovements(workspace, period, 100, BRL).size());
    }

    private Transaction add(TransactionType type, AccountId account, AccountId destination,
                            AdjustmentDirection direction, String amount, String date, TransactionStatus status,
                            Transaction refundOf) {
        Transaction transaction = create(anas, type, account, destination, direction, amount, LocalDate.parse(date),
                type.name() + " " + date, status, refundOf == null ? null : refundOf.id(), CREATED_AT);
        transactions.add(transaction);
        return transaction;
    }

    private void change(Transaction transaction, java.util.function.BiConsumer<Transaction, java.time.Instant> action) {
        Transaction stored = transactions.findById(anas, transaction.id()).orElseThrow();
        action.accept(stored, CREATED_AT.plusSeconds(1));
        transactions.save(stored);
    }

    private static Transaction tx(WorkspaceId workspace, TransactionType type, AccountId account, String amount,
                                  String date, TransactionStatus status) {
        return create(workspace, type, account, null, null, amount, LocalDate.parse(date), type.name(), status, null,
                CREATED_AT);
    }

    private static OverviewPeriod period(String from, String to) {
        return new OverviewPeriod(PeriodType.CUSTOM, LocalDate.parse(from), LocalDate.parse(to));
    }

    private WorkspaceId persistedWorkspace(User owner) {
        Workspace workspace = personalWorkspace(owner);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            users.addIfAbsent(owner);
            workspaces.addPersonalIfAbsent(workspace);
        });
        return workspace.id();
    }

    private AccountId persistedAccount(WorkspaceId workspaceId, String name, boolean includedInTotal) {
        Account account = includedInTotal ? account(workspaceId, name) : Account.create(AccountId.generate(CREATED_AT),
                workspaceId, new AccountName(name), AccountType.OTHER, null, BRL, false, CREATED_AT);
        accounts.add(account);
        return account.id();
    }

    private void archive(WorkspaceId workspace, AccountId id) {
        Account stored = accounts.findById(workspace, id).orElseThrow();
        stored.archive(CREATED_AT.plusSeconds(1));
        accounts.save(stored);
    }
}
