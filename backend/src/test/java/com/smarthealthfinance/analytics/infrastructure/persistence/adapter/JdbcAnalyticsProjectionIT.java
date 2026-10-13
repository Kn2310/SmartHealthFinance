package com.smarthealthfinance.analytics.infrastructure.persistence.adapter;

import com.smarthealthfinance.accounts.domain.enums.AccountType;
import com.smarthealthfinance.accounts.domain.model.Account;
import com.smarthealthfinance.accounts.domain.repository.AccountRepository;
import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.accounts.domain.valueobject.AccountName;
import com.smarthealthfinance.accounts.domain.valueobject.InstitutionName;
import com.smarthealthfinance.analytics.application.ReferenceAnalytics;
import com.smarthealthfinance.analytics.application.port.AnalyticsProjectionStore;
import com.smarthealthfinance.analytics.application.port.AnalyticsReadModel;
import com.smarthealthfinance.analytics.application.usecase.RebuildAnalyticsProjection;
import com.smarthealthfinance.analytics.application.usecase.RefreshAnalyticsProjection;
import com.smarthealthfinance.analytics.domain.enums.ProjectionState;
import com.smarthealthfinance.analytics.domain.model.CategoryFlow;
import com.smarthealthfinance.analytics.domain.model.DailyFlow;
import com.smarthealthfinance.analytics.domain.model.MerchantFlow;
import com.smarthealthfinance.analytics.domain.model.MonthlyFlow;
import com.smarthealthfinance.analytics.domain.valueobject.AnalyticsPeriod;
import com.smarthealthfinance.identity.IdentityTables;
import com.smarthealthfinance.identity.domain.model.User;
import com.smarthealthfinance.identity.domain.model.Workspace;
import com.smarthealthfinance.identity.domain.repository.UserRepository;
import com.smarthealthfinance.identity.domain.repository.WorkspaceRepository;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.support.IntegrationTest;
import com.smarthealthfinance.transactions.domain.enums.AdjustmentDirection;
import com.smarthealthfinance.transactions.domain.enums.TransactionStatus;
import com.smarthealthfinance.transactions.domain.enums.TransactionType;
import com.smarthealthfinance.transactions.domain.model.Transaction;
import com.smarthealthfinance.transactions.domain.repository.CategoryRepository;
import com.smarthealthfinance.transactions.domain.repository.TransactionRepository;
import com.smarthealthfinance.transactions.domain.service.SystemCategoryRules;
import com.smarthealthfinance.transactions.domain.valueobject.CategoryCode;
import com.smarthealthfinance.transactions.domain.valueobject.CategoryOrigin;
import com.smarthealthfinance.transactions.domain.valueobject.TransactionId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.smarthealthfinance.accounts.AccountsFixtures.account;
import static com.smarthealthfinance.identity.IdentityFixtures.CREATED_AT;
import static com.smarthealthfinance.identity.IdentityFixtures.activeUser;
import static com.smarthealthfinance.identity.IdentityFixtures.personalWorkspace;
import static com.smarthealthfinance.transactions.TransactionsFixtures.create;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Paridade da projeção de Analytics (ADR-0014 §5): o SQL (rebuild, recálculo por mês e leitura) contra a referência
 * em memória que usa a semântica do domínio, com todos os tipos, status, contas fora do total e arquivadas,
 * categorias e comerciantes. Mesmo padrão do {@code JdbcOverviewReadModelIT}.
 */
class JdbcAnalyticsProjectionIT extends IntegrationTest {

    private static final Currency BRL = Currency.getInstance("BRL");
    private static final YearMonth JUL = YearMonth.of(2026, 7);
    private static final YearMonth AUG = YearMonth.of(2026, 8);
    private static final YearMonth SEP = YearMonth.of(2026, 9);
    private static final List<AnalyticsPeriod> PERIODS = List.of(
            AnalyticsPeriod.of(SEP),
            AnalyticsPeriod.of(AUG),
            AnalyticsPeriod.months(JUL, SEP),
            new AnalyticsPeriod(LocalDate.parse("2026-09-03"), LocalDate.parse("2026-09-15")));

    @Autowired
    AnalyticsReadModel readModel;

    @Autowired
    AnalyticsProjectionStore store;

    @Autowired
    RebuildAnalyticsProjection rebuild;

    @Autowired
    RefreshAnalyticsProjection refresh;

    @Autowired
    TransactionRepository transactions;

    @Autowired
    CategoryRepository categories;

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

    private WorkspaceId anas;
    private WorkspaceId bobs;
    private final List<Account> anasAccounts = new ArrayList<>();
    private AccountId aurora;
    private AccountId reserva;
    private AccountId antiga;
    private Transaction lume;
    private Transaction lojaAlfa;
    private Transaction pending;

    @BeforeEach
    void setUp() {
        IdentityTables.clean(jdbc);
        anas = persistedWorkspace(activeUser("sub-ana"));
        bobs = persistedWorkspace(activeUser("sub-bob"));
        aurora = persistedAccount(account(anas, "Banco Aurora"));
        reserva = persistedAccount(Account.create(AccountId.generate(CREATED_AT), anas, new AccountName("Reserva"),
                AccountType.SAVINGS, new InstitutionName("Banco Norte"), BRL, false, CREATED_AT));
        Account old = account(anas, "Conta antiga");
        antiga = persistedAccount(old);
        seed();
        // Arquivada depois dos lançamentos: as movimentações dela saem do fluxo, como no Overview.
        jdbc.update("update accounts set status = 'ARCHIVED' where id = ?", antiga.value());
        anasAccounts.replaceAll(account -> accounts.findById(anas, account.id()).orElseThrow());
    }

    @Test
    void rebuiltProjectionMatchesTheDomainReferenceForEveryQueryAndPeriod() {
        rebuild.execute(anas);

        assertParity();
        // Sanidade: a paridade não pode passar por vacuidade.
        assertThat(readModel.byMerchant(anas, AnalyticsPeriod.of(SEP), BRL)).hasSizeGreaterThan(3);
        assertThat(readModel.monthly(anas, AnalyticsPeriod.months(JUL, SEP), BRL)).hasSize(3);
    }

    @Test
    void recomputingAMonthAfterChangesKeepsParity() {
        rebuild.execute(anas);

        inTransaction(() -> {
            Transaction reversed = transactions.findById(anas, lume.id()).orElseThrow();
            reversed.reverse(Instant.now());
            transactions.save(reversed);
            Transaction categorized = transactions.findById(anas, lojaAlfa.id()).orElseThrow();
            categorized.categorize(category("SHOPPING"), Instant.now());
            transactions.save(categorized);
            Transaction posted = transactions.findById(anas, pending.id()).orElseThrow();
            posted.post(Instant.now());
            transactions.save(posted);
        });
        refresh.execute(UUID.randomUUID(), Instant.now(), anas, Set.of(SEP));

        assertParity();
    }

    @Test
    void eventPublishedBeforeTheLastRecalculationIsCoalesced() {
        rebuild.execute(anas);
        Instant beforeRebuild = Instant.now().minusSeconds(60);
        inTransaction(() -> {
            Transaction reversed = transactions.findById(anas, lume.id()).orElseThrow();
            reversed.reverse(Instant.now());
            transactions.save(reversed);
        });

        // Evento "antigo": o mês foi recalculado depois dele, então nada muda (e a reversão ainda não aparece).
        refresh.execute(UUID.randomUUID(), beforeRebuild, anas, Set.of(SEP));
        assertThat(merchantTotal("bistro lume")).isEqualTo("86.40");

        // O evento da reversão, publicado agora, recalcula o mês.
        refresh.execute(UUID.randomUUID(), Instant.now(), anas, Set.of(SEP));
        assertThat(merchantTotal("bistro lume")).isNull();
        assertParity();
    }

    @Test
    void recomputingIsIdempotent() {
        rebuild.execute(anas);
        String before = projectionRows();

        refresh.execute(UUID.randomUUID(), Instant.now(), anas, Set.of(JUL, AUG, SEP));
        refresh.execute(UUID.randomUUID(), Instant.now(), anas, Set.of(SEP));
        rebuild.execute(anas);

        assertThat(projectionRows()).isEqualTo(before);
    }

    @Test
    void accountSettingsApplyAtReadTimeWithoutRecomputing() {
        rebuild.execute(anas);

        jdbc.update("update accounts set included_in_total = false where id = ?", aurora.value());
        anasAccounts.replaceAll(account -> accounts.findById(anas, account.id()).orElseThrow());

        assertThat(readModel.monthly(anas, AnalyticsPeriod.of(SEP), BRL).getFirst().totals().income())
                .isEqualTo(Money.zero(BRL));
        assertParity();
    }

    @Test
    void workspacesNeverMix() {
        rebuild.execute(anas);
        rebuild.execute(bobs);

        assertThat(readModel.byMerchant(bobs, AnalyticsPeriod.of(SEP), BRL))
                .extracting(MerchantFlow::merchantKey).containsExactly("padaria do bob");
        assertThat(readModel.byMerchant(anas, AnalyticsPeriod.of(SEP), BRL))
                .extracting(MerchantFlow::merchantKey).doesNotContain("padaria do bob");
        assertThat(jdbc.queryForObject("select count(*) from analytics_daily_flows where workspace_id = ?",
                Integer.class, bobs.value())).isEqualTo(1);
    }

    @Test
    void rebuildScopesAndFreshness() {
        assertThat(readModel.freshness(anas).state()).isEqualTo(ProjectionState.NOT_BUILT);
        assertThat(store.workspacesToRebuild(RebuildAnalyticsProjection.VERSION, false))
                .containsExactlyInAnyOrder(anas, bobs);

        rebuild.execute(anas);
        assertThat(store.workspacesToRebuild(RebuildAnalyticsProjection.VERSION, false)).containsExactly(bobs);
        assertThat(rebuild.execute(RebuildAnalyticsProjection.Scope.MISSING)).isEqualTo(1);
        assertThat(store.workspacesToRebuild(RebuildAnalyticsProjection.VERSION, false)).isEmpty();
        assertThat(store.workspacesToRebuild(RebuildAnalyticsProjection.VERSION, true)).hasSize(2);

        var fresh = readModel.freshness(anas);
        assertThat(fresh.state()).isEqualTo(ProjectionState.UP_TO_DATE);
        assertThat(fresh.refreshedAt()).isNotNull();

        // Evento do Workspace posterior ao rebuild e ainda não processado pelo Analytics. Já marcado como publicado
        // para o relay não entregá-lo de verdade durante o teste.
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                        insert into outbox_events (id, event_type, event_version, workspace_id, aggregate_type,
                          aggregate_id, payload, occurred_at, published_at)
                        values (?, 'transaction.categorized', 1, ?, 'Transaction', ?, '{}'::jsonb, ?, ?)
                        """,
                eventId, anas.value(), UUID.randomUUID(), Timestamp.from(Instant.now().plusSeconds(1)),
                Timestamp.from(Instant.now().plusSeconds(1)));
        assertThat(readModel.freshness(anas).state()).isEqualTo(ProjectionState.UPDATING);
        assertThat(readModel.freshness(anas).pendingEvents()).isEqualTo(1);
        assertThat(readModel.freshness(bobs).state()).isEqualTo(ProjectionState.UP_TO_DATE);

        jdbc.update("insert into processed_events (consumer, event_id, processed_at) values (?, ?, now())",
                RefreshAnalyticsProjection.CONSUMER, eventId);
        assertThat(readModel.freshness(anas).state()).isEqualTo(ProjectionState.UP_TO_DATE);
    }

    @Test
    void firstEventOfAWorkspaceBuildsTheWholeProjection() {
        assertThat(refresh.execute(UUID.randomUUID(), Instant.now(), anas, Set.of(SEP)))
                .isEqualTo(RefreshAnalyticsProjection.Outcome.REBUILT);

        assertParity();
    }

    // --- dados ---

    private void seed() {
        Transaction salary = tx(TransactionType.INCOME, aurora, null, null, "6500.00", "2026-09-01",
                "Salário - Empresa Fictícia Ltda", TransactionStatus.POSTED, null);
        salary.categorizeByRule(category("SALARY"), CategoryOrigin.system(SystemCategoryRules.VERSION), CREATED_AT);
        Transaction freelance = tx(TransactionType.INCOME, aurora, null, null, "300.00", "2026-09-10",
                "Pix recebido - Freelance Estúdio Ponto", TransactionStatus.POSTED, null);
        Transaction market1 = categorized(tx(TransactionType.EXPENSE, aurora, null, null, "120.50", "2026-09-03",
                "Mercado Bom Preço", TransactionStatus.POSTED, null), "GROCERIES");
        Transaction market2 = categorized(tx(TransactionType.EXPENSE, aurora, null, null, "80.00", "2026-09-15",
                "Mercado Bom Preço", TransactionStatus.POSTED, null), "GROCERIES");
        lume = categorized(tx(TransactionType.EXPENSE, aurora, null, null, "86.40", "2026-09-22", "Bistrô Lume",
                TransactionStatus.POSTED, null), "DINING_OUT");
        Transaction lumeAugust = categorized(tx(TransactionType.EXPENSE, aurora, null, null, "40.00", "2026-08-05",
                "Bistrô Lume", TransactionStatus.POSTED, null), "DINING_OUT");
        lojaAlfa = tx(TransactionType.EXPENSE, aurora, null, null, "50.00", "2026-09-22", "Loja Alfa",
                TransactionStatus.POSTED, null);
        Transaction outsideTotal = categorized(tx(TransactionType.EXPENSE, reserva, null, null, "12.30", "2026-09-22",
                "Café Grão", TransactionStatus.POSTED, null), "DINING_OUT");
        Transaction archived = tx(TransactionType.EXPENSE, antiga, null, null, "220.50", "2026-07-14",
                "Posto de Combustível Central", TransactionStatus.POSTED, null);
        pending = tx(TransactionType.EXPENSE, aurora, null, null, "999.00", "2026-09-20", "Loja Gama",
                TransactionStatus.PENDING, null);
        Transaction cancelled = tx(TransactionType.EXPENSE, aurora, null, null, "500.00", "2026-09-20", "Loja Delta",
                TransactionStatus.PENDING, null);
        cancelled.cancel(CREATED_AT);
        Transaction reversed = tx(TransactionType.EXPENSE, aurora, null, null, "77.00", "2026-09-08", "Loja Beta",
                TransactionStatus.POSTED, null);
        reversed.reverse(CREATED_AT);
        Transaction refund = categorized(tx(TransactionType.REFUND, aurora, null, null, "30.00", "2026-09-23",
                "Estorno Bistrô Lume", TransactionStatus.POSTED, lume.id()), "DINING_OUT");
        Transaction toReserve = tx(TransactionType.TRANSFER, aurora, reserva, null, "500.00", "2026-09-05",
                "Transferência para Reserva", TransactionStatus.POSTED, null);
        Transaction fromOld = tx(TransactionType.TRANSFER, antiga, aurora, null, "100.00", "2026-08-02",
                "Transferência da conta antiga", TransactionStatus.POSTED, null);
        Transaction opening = tx(TransactionType.ADJUSTMENT, aurora, null, AdjustmentDirection.INCREASE, "1000.00",
                "2026-09-01", "Saldo inicial", TransactionStatus.POSTED, null);
        Transaction bobs = create(this.bobs, TransactionType.EXPENSE, persistedAccount(account(this.bobs, "Conta do Bob")),
                null, null, "10.00", LocalDate.parse("2026-09-22"), "Padaria do Bob", TransactionStatus.POSTED, null,
                CREATED_AT);

        inTransaction(() -> transactions.addAll(List.of(salary, freelance, market1, market2, lume, lumeAugust,
                lojaAlfa, outsideTotal, archived, pending, cancelled, reversed, refund, toReserve, fromOld, opening,
                bobs)));
    }

    private Transaction tx(TransactionType type, AccountId account, AccountId destination,
                           AdjustmentDirection direction, String amount, String date, String description,
                           TransactionStatus status, TransactionId refundOf) {
        return create(anas, type, account, destination, direction, amount, LocalDate.parse(date), description, status,
                refundOf, CREATED_AT);
    }

    private Transaction categorized(Transaction transaction, String code) {
        transaction.categorize(category(code), CREATED_AT);
        return transaction;
    }

    // --- paridade ---

    private void assertParity() {
        ReferenceAnalytics reference = new ReferenceAnalytics(
                transactions.search(anas, com.smarthealthfinance.transactions.domain.repository.TransactionCriteria
                        .none(), 0, 1000).items(), anasAccounts, BRL);

        assertThat(readModel.firstActivity(anas)).isEqualTo(reference.firstActivity());

        for (AnalyticsPeriod period : PERIODS) {
            assertThat(monthly(readModel.monthly(anas, period, BRL))).as("monthly %s", period)
                    .containsExactlyElementsOf(monthly(reference.monthly(period)));
            assertThat(categories(readModel.byCategory(anas, period, BRL))).as("byCategory %s", period)
                    .containsExactlyInAnyOrderElementsOf(categories(reference.byCategory(period)));
            assertThat(merchants(readModel.byMerchant(anas, period, BRL))).as("byMerchant %s", period)
                    .containsExactlyInAnyOrderElementsOf(merchants(reference.byMerchant(period)));
            assertThat(daily(readModel.daily(anas, period, BRL))).as("daily %s", period)
                    .containsExactlyInAnyOrderElementsOf(daily(reference.daily(period)));
            assertThat(entries(readModel.entries(anas, period, BRL))).as("entries %s", period)
                    .containsExactlyInAnyOrderElementsOf(entries(reference.entries(period)));
            assertThat(expenses(readModel.largestExpenses(anas, period, 3, BRL))).as("largestExpenses %s", period)
                    .containsExactlyElementsOf(expenses(reference.largestExpenses(period, 3)));
        }
    }

    private static List<String> monthly(List<MonthlyFlow> rows) {
        return rows.stream().map(r -> r.month() + " in=" + plain(r.totals().income()) + " out="
                + plain(r.totals().expense()) + " ref=" + plain(r.totals().refunds()) + " tr="
                + r.totals().transferCount() + "/" + plain(r.totals().transferVolume()) + " net="
                + plain(r.totals().net())).toList();
    }

    private static List<String> categories(List<CategoryFlow> rows) {
        return rows.stream().map(r -> r.role() + "|" + r.categoryCode() + "|" + plain(r.amount()) + "|"
                + r.transactionCount()).toList();
    }

    private static List<String> merchants(List<MerchantFlow> rows) {
        return rows.stream().map(r -> r.role() + "|" + r.merchantKey() + "|" + r.merchantName() + "|"
                + r.categoryCode() + "|" + plain(r.amount()) + "|" + r.transactionCount()).toList();
    }

    private static List<String> daily(List<DailyFlow> rows) {
        return rows.stream().map(r -> r.day() + "|" + r.role() + "|" + plain(r.amount()) + "|"
                + r.transactionCount()).toList();
    }

    private static List<String> entries(List<com.smarthealthfinance.analytics.domain.model.FlowEntry> rows) {
        return rows.stream().map(r -> r.day() + "|" + r.role() + "|" + r.categoryCode() + "|" + r.merchantKey() + "|"
                + r.merchantName() + "|" + plain(r.amount()) + "|" + r.transactionCount()).toList();
    }

    private static List<String> expenses(List<com.smarthealthfinance.analytics.domain.model.ExpenseItem> rows) {
        return rows.stream().map(r -> r.transactionId() + "|" + r.occurredOn() + "|" + r.description() + "|"
                + r.merchantName() + "|" + r.categoryCode() + "|" + plain(r.amount())).toList();
    }

    private static String plain(Money money) {
        return money.amount().setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    private String merchantTotal(String merchantKey) {
        return readModel.byMerchant(anas, AnalyticsPeriod.of(SEP), BRL).stream()
                .filter(row -> row.role() == com.smarthealthfinance.transactions.domain.enums.CashFlowRole.EXPENSE)
                .filter(row -> merchantKey.equals(row.merchantKey()))
                .map(row -> plain(row.amount()))
                .findFirst().orElse(null);
    }

    private String projectionRows() {
        return jdbc.queryForObject("""
                select coalesce(string_agg(f::text, '|' order by f::text), '')
                from (select workspace_id, day, account_id, flow, category_code, merchant_key, merchant_name, currency,
                             amount, transaction_count from analytics_daily_flows) f
                """, String.class);
    }

    // --- infraestrutura ---

    private com.smarthealthfinance.transactions.domain.model.Category category(String code) {
        return categories.findByCode(new CategoryCode(code)).orElseThrow();
    }

    private void inTransaction(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
    }

    private WorkspaceId persistedWorkspace(User owner) {
        Workspace workspace = personalWorkspace(owner);
        inTransaction(() -> {
            users.addIfAbsent(owner);
            workspaces.addPersonalIfAbsent(workspace);
        });
        return workspace.id();
    }

    private AccountId persistedAccount(Account account) {
        accounts.add(account);
        if (account.workspaceId().equals(anas)) {
            anasAccounts.add(account);
        }
        return account.id();
    }
}
