package com.smarthealthfinance.overview.application.usecase;

import com.smarthealthfinance.accounts.domain.enums.AccountType;
import com.smarthealthfinance.accounts.domain.model.Account;
import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.accounts.domain.valueobject.AccountName;
import com.smarthealthfinance.identity.application.exception.WorkspaceNotFoundException;
import com.smarthealthfinance.identity.domain.model.User;
import com.smarthealthfinance.identity.domain.model.Workspace;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.overview.application.ReferenceOverviewReadModel;
import com.smarthealthfinance.overview.application.dto.OverviewView;
import com.smarthealthfinance.overview.application.port.OverviewReadModel;
import com.smarthealthfinance.overview.domain.enums.MovementFlow;
import com.smarthealthfinance.overview.domain.enums.OverviewState;
import com.smarthealthfinance.overview.domain.enums.PeriodType;
import com.smarthealthfinance.overview.domain.model.CashFlow;
import com.smarthealthfinance.overview.domain.valueobject.OverviewPeriod;
import com.smarthealthfinance.transactions.application.TransactionsTestContext;
import com.smarthealthfinance.transactions.domain.enums.AdjustmentDirection;
import com.smarthealthfinance.transactions.domain.enums.TransactionStatus;
import com.smarthealthfinance.transactions.domain.enums.TransactionType;
import com.smarthealthfinance.transactions.domain.model.Transaction;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.smarthealthfinance.identity.IdentityFixtures.CREATED_AT;
import static com.smarthealthfinance.identity.IdentityFixtures.activeUser;
import static com.smarthealthfinance.identity.IdentityFixtures.personalWorkspace;
import static com.smarthealthfinance.shared.domain.InvalidValueAssert.assertInvalidValue;
import static com.smarthealthfinance.transactions.TransactionsFixtures.brl;
import static com.smarthealthfinance.transactions.TransactionsFixtures.create;
import static com.smarthealthfinance.transactions.TransactionsFixtures.expense;
import static com.smarthealthfinance.transactions.TransactionsFixtures.income;
import static com.smarthealthfinance.transactions.TransactionsFixtures.refund;
import static com.smarthealthfinance.transactions.TransactionsFixtures.transfer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Hoje nos testes: 2026-09-27 (mês corrente = 01/09 a 27/09). */
class GetFinancialOverviewTest {

    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");

    private final TransactionsTestContext ctx = new TransactionsTestContext();
    private final WorkspaceId ws = ctx.anasWorkspace.id();
    private final GetFinancialOverview overview = overviewAt(ctx.clock);

    private GetFinancialOverview overviewAt(Clock clock) {
        return new GetFinancialOverview(ctx.currentUserService,
                new com.smarthealthfinance.identity.application.service.WorkspaceAccessGuard(ctx.workspaces),
                ctx.accounts, new ReferenceOverviewReadModel(ctx.transactions, ctx.accounts), clock, SAO_PAULO,
                new SimpleMeterRegistry());
    }

    // --- estados sem dados ---

    @Test
    void newWorkspaceWithoutAccountsHasNoDataAtAll() {
        User carol = activeUser("sub-carol");
        Workspace carolsWorkspace = personalWorkspace(carol);
        ctx.users.store(carol);
        ctx.workspaces.store(carolsWorkspace);
        ctx.actAs(carol);

        OverviewView view = overview.execute(carolsWorkspace.id().value(), current());

        assertThat(view.state()).isEqualTo(OverviewState.NO_ACCOUNTS);
        assertThat(view.summary().totalBalance()).isNull();
        assertThat(view.cashFlow()).isNull();
        assertThat(view.accounts()).isEmpty();
        assertThat(view.recentTransactions()).isEmpty();
        assertThat(view.summary().accountCount()).isZero();
    }

    @Test
    void accountsWithoutTransactionsHaveNoBalanceInsteadOfZero() {
        OverviewView view = overview.execute(ctx.anasWorkspaceId(), current());

        assertThat(view.state()).isEqualTo(OverviewState.NO_TRANSACTIONS);
        assertThat(view.summary().totalBalance()).isNull();
        assertThat(view.cashFlow()).isNull();
        assertThat(view.summary().accountCount()).isEqualTo(2);
        assertThat(view.accounts()).extracting(OverviewView.AccountSummary::name)
                .containsExactly("Banco Aurora", "Banco Norte");
        assertThat(view.accounts()).allSatisfy(account -> {
            assertThat(account.balance()).isNull();
            assertThat(account.movementCount()).isZero();
        });
    }

    @Test
    void onlyPendingTransactionsStillHaveNoFinancialData() {
        ctx.store(create(ws, TransactionType.EXPENSE, ctx.aurora.id(), null, null, "50", day("2026-09-20"), "Conta de luz",
                TransactionStatus.PENDING, null, CREATED_AT));

        OverviewView view = overview.execute(ctx.anasWorkspaceId(), current());

        assertThat(view.state()).isEqualTo(OverviewState.NO_TRANSACTIONS);
        assertThat(view.summary().totalBalance()).isNull();
        assertThat(view.summary().pendingTransactions()).isEqualTo(1);
        assertThat(view.recentTransactions()).extracting(OverviewView.RecentTransaction::description)
                .containsExactly("Conta de luz");
        assertThat(view.recentTransactions()).extracting(OverviewView.RecentTransaction::status)
                .containsOnly(TransactionStatus.PENDING);
    }

    /**
     * ADR-0006 §13 (emenda de 2026-10-09): saldo por conta e fluxo de caixa só consultam com dados financeiros
     * (READY/NO_ACTIVITY_IN_PERIOD); a atividade recente consulta sempre que há conta ativa — em NO_TRANSACTIONS
     * ela traz só PENDING. Sem conta ativa, nenhuma consulta de transações roda.
     */
    @Test
    void queriesRunPerStateAsTheAdrDescribes() {
        RecordingReadModel readModel = new RecordingReadModel(new ReferenceOverviewReadModel(ctx.transactions, ctx.accounts));
        GetFinancialOverview recorded = new GetFinancialOverview(ctx.currentUserService,
                new com.smarthealthfinance.identity.application.service.WorkspaceAccessGuard(ctx.workspaces),
                ctx.accounts, readModel, ctx.clock, SAO_PAULO, new SimpleMeterRegistry());

        User carol = activeUser("sub-carol");
        Workspace carolsWorkspace = personalWorkspace(carol);
        ctx.users.store(carol);
        ctx.workspaces.store(carolsWorkspace);
        ctx.actAs(carol);
        assertThat(recorded.execute(carolsWorkspace.id().value(), current()).state()).isEqualTo(OverviewState.NO_ACCOUNTS);
        assertThat(readModel.calls).isEmpty();

        ctx.actAs(ctx.ana);
        ctx.store(create(ws, TransactionType.EXPENSE, ctx.aurora.id(), null, null, "50", day("2026-09-20"), "Conta de luz",
                TransactionStatus.PENDING, null, CREATED_AT));
        assertThat(recorded.execute(ctx.anasWorkspaceId(), current()).state()).isEqualTo(OverviewState.NO_TRANSACTIONS);
        assertThat(readModel.calls).containsExactly("activity", "recentMovements");

        readModel.calls.clear();
        ctx.store(income(ws, ctx.aurora.id(), "1000", day("2026-09-10"), "Salário"));
        assertThat(recorded.execute(ctx.anasWorkspaceId(), current()).state()).isEqualTo(OverviewState.READY);
        assertThat(readModel.calls).containsExactlyInAnyOrder("activity", "recentMovements", "accountFigures", "cashFlow");
    }

    /** Registra quais consultas o caso de uso dispara, delegando à implementação de referência. */
    private static final class RecordingReadModel implements OverviewReadModel {

        final List<String> calls = new ArrayList<>();
        private final OverviewReadModel delegate;

        RecordingReadModel(OverviewReadModel delegate) {
            this.delegate = delegate;
        }

        @Override
        public Map<AccountId, AccountFigures> accountFigures(WorkspaceId workspaceId, OverviewPeriod period,
                                                                    Currency currency) {
            calls.add("accountFigures");
            return delegate.accountFigures(workspaceId, period, currency);
        }

        @Override
        public CashFlow cashFlow(WorkspaceId workspaceId, OverviewPeriod period, Currency currency) {
            calls.add("cashFlow");
            return delegate.cashFlow(workspaceId, period, currency);
        }

        @Override
        public Activity activity(WorkspaceId workspaceId, OverviewPeriod period, Currency currency) {
            calls.add("activity");
            return delegate.activity(workspaceId, period, currency);
        }

        @Override
        public List<RecentMovement> recentMovements(WorkspaceId workspaceId, OverviewPeriod period, int limit,
                                                              Currency currency) {
            calls.add("recentMovements");
            return delegate.recentMovements(workspaceId, period, limit, currency);
        }
    }

    @Test
    void historyOutsideThePeriodGivesRealZerosNotAbsence() {
        ctx.store(income(ws, ctx.aurora.id(), "1000", day("2026-08-10"), "Salário"));

        OverviewView view = overview.execute(ctx.anasWorkspaceId(), current());

        assertThat(view.state()).isEqualTo(OverviewState.NO_ACTIVITY_IN_PERIOD);
        assertThat(view.summary().totalBalance()).isEqualTo(brl("1000"));
        assertThat(view.cashFlow().income()).isEqualTo(brl("0"));
        assertThat(view.cashFlow().expense()).isEqualTo(brl("0"));
        assertThat(view.cashFlow().net()).isEqualTo(brl("0"));
        assertThat(view.accounts()).extracting(OverviewView.AccountSummary::balance)
                .containsExactly(brl("1000"), brl("0"));
        assertThat(view.recentTransactions()).isEmpty();
    }

    // --- cálculo ---

    @Test
    void consolidatesBalanceAndCashFlowForTheCurrentMonth() {
        populateSeptember();

        OverviewView view = overview.execute(ctx.anasWorkspaceId(), current());

        assertThat(view.state()).isEqualTo(OverviewState.READY);
        assertThat(view.period().type()).isEqualTo(PeriodType.CURRENT_MONTH);
        assertThat(view.period().from()).isEqualTo(day("2026-09-01"));
        assertThat(view.period().to()).isEqualTo(day("2026-09-27"));
        assertThat(view.summary().balanceAsOf()).isEqualTo(day("2026-09-27"));
        // aurora: +1200 +6800 -86.40 -296.08 -500 +30 -20 = 7127.52 ; norte: +500
        assertThat(view.summary().totalBalance()).isEqualTo(brl("7627.52"));
        assertThat(view.accounts()).extracting(OverviewView.AccountSummary::balance)
                .containsExactly(brl("7127.52"), brl("500"));
        assertThat(view.accounts()).extracting(OverviewView.AccountSummary::movementCount).containsExactly(7, 1);
        assertThat(view.cashFlow().income()).isEqualTo(brl("6800"));
        assertThat(view.cashFlow().expense()).isEqualTo(brl("382.48"));
        assertThat(view.cashFlow().refunds()).isEqualTo(brl("30"));
        assertThat(view.cashFlow().net()).isEqualTo(brl("6447.52"));
    }

    @Test
    void transferIsNeitherIncomeNorExpenseAndKeepsTheTotal() {
        ctx.store(income(ws, ctx.aurora.id(), "1000", day("2026-09-02"), "Salário"));
        ctx.store(transfer(ws, ctx.aurora.id(), ctx.norte.id(), "400"));

        OverviewView view = overview.execute(ctx.anasWorkspaceId(), current());

        assertThat(view.summary().totalBalance()).isEqualTo(brl("1000"));
        assertThat(view.accounts()).extracting(OverviewView.AccountSummary::balance)
                .containsExactly(brl("600"), brl("400"));
        assertThat(view.cashFlow().income()).isEqualTo(brl("1000"));
        assertThat(view.cashFlow().expense()).isEqualTo(brl("0"));
        assertThat(view.cashFlow().net()).isEqualTo(brl("1000"));
        assertThat(view.cashFlow().transferCount()).isEqualTo(1);
        assertThat(view.cashFlow().transferVolume()).isEqualTo(brl("400"));
    }

    @Test
    void refundReducesTheResultAndIsNeverIncome() {
        Transaction purchase = ctx.store(expense(ws, ctx.aurora.id(), "100", day("2026-09-10"), "Loja", CREATED_AT));
        ctx.store(refund(ws, ctx.aurora.id(), "40", purchase));

        OverviewView view = overview.execute(ctx.anasWorkspaceId(), current());

        assertThat(view.cashFlow().income()).isEqualTo(brl("0"));
        assertThat(view.cashFlow().expense()).isEqualTo(brl("100"));
        assertThat(view.cashFlow().refunds()).isEqualTo(brl("40"));
        assertThat(view.cashFlow().net()).isEqualTo(brl("-60"));
        assertThat(view.summary().totalBalance()).isEqualTo(brl("-60"));
    }

    @Test
    void refundOfAPreviousMonthPurchaseDoesNotMakeExpenseNegative() {
        Transaction august = ctx.store(expense(ws, ctx.aurora.id(), "100", day("2026-08-20"), "Loja", CREATED_AT));
        ctx.store(refund(ws, ctx.aurora.id(), "100", august));

        OverviewView view = overview.execute(ctx.anasWorkspaceId(), current());

        assertThat(view.cashFlow().expense()).isEqualTo(brl("0"));
        assertThat(view.cashFlow().refunds()).isEqualTo(brl("100"));
        assertThat(view.cashFlow().net()).isEqualTo(brl("100"));
    }

    @Test
    void adjustmentsMoveBalanceButNotCashFlow() {
        ctx.store(create(ws, TransactionType.ADJUSTMENT, ctx.aurora.id(), null, AdjustmentDirection.INCREASE, "900",
                day("2026-09-03"), "Saldo inicial", TransactionStatus.POSTED, null, CREATED_AT));
        ctx.store(create(ws, TransactionType.ADJUSTMENT, ctx.aurora.id(), null, AdjustmentDirection.DECREASE, "150",
                day("2026-09-04"), "Correção", TransactionStatus.POSTED, null, CREATED_AT));

        OverviewView view = overview.execute(ctx.anasWorkspaceId(), current());

        assertThat(view.summary().totalBalance()).isEqualTo(brl("750"));
        assertThat(view.cashFlow().income()).isEqualTo(brl("0"));
        assertThat(view.cashFlow().expense()).isEqualTo(brl("0"));
        assertThat(view.cashFlow().net()).isEqualTo(brl("0"));
        assertThat(view.state()).isEqualTo(OverviewState.READY);
    }

    @Test
    void negativeBalancesAreRepresented() {
        ctx.store(expense(ws, ctx.aurora.id(), "250.75", day("2026-09-05"), "Aluguel", CREATED_AT));

        OverviewView view = overview.execute(ctx.anasWorkspaceId(), current());

        assertThat(view.summary().totalBalance()).isEqualTo(brl("-250.75"));
        assertThat(view.cashFlow().net()).isEqualTo(brl("-250.75"));
    }

    // --- status ---

    @Test
    void pendingCancelledAndReversedNeverEnterBalanceOrCashFlow() {
        ctx.store(income(ws, ctx.aurora.id(), "1000", day("2026-09-02"), "Salário"));
        ctx.store(create(ws, TransactionType.EXPENSE, ctx.aurora.id(), null, null, "70", day("2026-09-10"), "Pendente",
                TransactionStatus.PENDING, null, CREATED_AT));
        Transaction cancelled = create(ws, TransactionType.EXPENSE, ctx.aurora.id(), null, null, "80", day("2026-09-11"),
                "Cancelada", TransactionStatus.PENDING, null, CREATED_AT);
        cancelled.cancel(CREATED_AT);
        ctx.store(cancelled);
        Transaction reversed = expense(ws, ctx.aurora.id(), "90", day("2026-09-12"), "Estornada", CREATED_AT);
        reversed.reverse(CREATED_AT);
        ctx.store(reversed);

        OverviewView view = overview.execute(ctx.anasWorkspaceId(), current());

        assertThat(view.summary().totalBalance()).isEqualTo(brl("1000"));
        assertThat(view.cashFlow().expense()).isEqualTo(brl("0"));
        assertThat(view.summary().pendingTransactions()).isEqualTo(1);
        assertThat(view.recentTransactions()).extracting(OverviewView.RecentTransaction::description)
                .containsExactly("Pendente", "Salário");
        assertThat(view.accounts().getFirst().movementCount()).isEqualTo(1);
    }

    @Test
    void postingAPendingTransactionBringsItIntoTheNumbers() {
        Transaction pending = create(ws, TransactionType.EXPENSE, ctx.aurora.id(), null, null, "70", day("2026-09-10"),
                "Conta de luz", TransactionStatus.PENDING, null, CREATED_AT);
        ctx.store(income(ws, ctx.aurora.id(), "1000", day("2026-09-02"), "Salário"));
        ctx.store(pending);
        pending.post(CREATED_AT);
        ctx.store(pending);

        OverviewView view = overview.execute(ctx.anasWorkspaceId(), current());

        assertThat(view.summary().totalBalance()).isEqualTo(brl("930"));
        assertThat(view.summary().pendingTransactions()).isZero();
    }

    // --- períodos ---

    @Test
    void previousMonthMeasuresBalanceAtItsEndAndCountsOnlyItsFlow() {
        populateSeptember();
        ctx.store(income(ws, ctx.aurora.id(), "2000", day("2026-08-05"), "Salário de agosto"));
        ctx.store(expense(ws, ctx.aurora.id(), "300", day("2026-08-31"), "Último dia", CREATED_AT));

        OverviewView view = overview.execute(ctx.anasWorkspaceId(), new GetFinancialOverview.Query("PREVIOUS_MONTH", null,
                null, null));

        assertThat(view.period().from()).isEqualTo(day("2026-08-01"));
        assertThat(view.period().to()).isEqualTo(day("2026-08-31"));
        assertThat(view.summary().totalBalance()).isEqualTo(brl("1700"));
        assertThat(view.cashFlow().income()).isEqualTo(brl("2000"));
        assertThat(view.cashFlow().expense()).isEqualTo(brl("300"));
        assertThat(view.cashFlow().net()).isEqualTo(brl("1700"));
        assertThat(view.recentTransactions()).extracting(OverviewView.RecentTransaction::description)
                .containsExactly("Último dia", "Salário de agosto");
    }

    @Test
    void customPeriodBoundariesAreInclusive() {
        ctx.store(income(ws, ctx.aurora.id(), "1", day("2026-09-04"), "Antes"));
        ctx.store(income(ws, ctx.aurora.id(), "10", day("2026-09-05"), "Primeiro dia"));
        ctx.store(income(ws, ctx.aurora.id(), "100", day("2026-09-10"), "Último dia"));
        ctx.store(income(ws, ctx.aurora.id(), "1000", day("2026-09-11"), "Depois"));

        OverviewView view = overview.execute(ctx.anasWorkspaceId(),
                new GetFinancialOverview.Query("CUSTOM", day("2026-09-05"), day("2026-09-10"), null));

        assertThat(view.period().type()).isEqualTo(PeriodType.CUSTOM);
        assertThat(view.cashFlow().income()).isEqualTo(brl("110"));
        assertThat(view.summary().totalBalance()).isEqualTo(brl("111"));
        assertThat(view.accounts().getFirst().movementCount()).isEqualTo(2);
    }

    @Test
    void transactionsAfterTheBalanceDateAreNotInTheBalance() {
        ctx.store(income(ws, ctx.aurora.id(), "1000", day("2026-09-02"), "Salário"));
        ctx.store(income(ws, ctx.aurora.id(), "500", day("2026-09-30"), "Futuro"));

        OverviewView view = overview.execute(ctx.anasWorkspaceId(), current());

        assertThat(view.summary().totalBalance()).isEqualTo(brl("1000"));
    }

    @Test
    void todayFollowsTheBusinessTimezoneNotUtc() {
        // 2026-10-01T01:00Z ainda é 30/09 22:00 em São Paulo.
        GetFinancialOverview lateEvening = overviewAt(Clock.fixed(Instant.parse("2026-10-01T01:00:00Z"), ZoneOffset.UTC));
        ctx.store(income(ws, ctx.aurora.id(), "10", day("2026-09-30"), "Noite"));

        OverviewView view = lateEvening.execute(ctx.anasWorkspaceId(), current());

        assertThat(view.period().from()).isEqualTo(day("2026-09-01"));
        assertThat(view.period().to()).isEqualTo(day("2026-09-30"));
        assertThat(view.cashFlow().income()).isEqualTo(brl("10"));
    }

    @Test
    void invalidPeriodParametersAreRejected() {
        UUID id = ctx.anasWorkspaceId();

        assertInvalidValue(() -> overview.execute(id, new GetFinancialOverview.Query("WEEK", null, null, null)),
                "period", "INVALID");
        assertInvalidValue(() -> overview.execute(id, new GetFinancialOverview.Query("CUSTOM", null, null, null)),
                "from", "REQUIRED");
        assertInvalidValue(() -> overview.execute(id,
                new GetFinancialOverview.Query("CUSTOM", day("2026-09-10"), day("2026-09-01"), null)), "to", "BEFORE_FROM");
        assertInvalidValue(() -> overview.execute(id,
                new GetFinancialOverview.Query("CURRENT_MONTH", day("2026-09-01"), null, null)), "from", "NOT_ALLOWED");
    }

    // --- contas ---

    @Test
    void accountExcludedFromTotalIsListedButNotSummedNorCounted() {
        Account brokerage = other("Corretora", false);
        ctx.store(income(ws, ctx.aurora.id(), "1000", day("2026-09-02"), "Salário"));
        ctx.store(income(ws, brokerage.id(), "5000", day("2026-09-03"), "Dividendos"));

        OverviewView view = overview.execute(ctx.anasWorkspaceId(), current());

        assertThat(view.summary().totalBalance()).isEqualTo(brl("1000"));
        assertThat(view.cashFlow().income()).isEqualTo(brl("1000"));
        assertThat(view.accounts()).extracting(OverviewView.AccountSummary::name)
                .containsExactly("Banco Aurora", "Banco Norte", "Corretora");
        OverviewView.AccountSummary listed = view.accounts().getLast();
        assertThat(listed.includedInTotal()).isFalse();
        assertThat(listed.balance()).isEqualTo(brl("5000"));
        assertThat(view.recentTransactions()).extracting(OverviewView.RecentTransaction::description)
                .contains("Dividendos");
    }

    @Test
    void archivedAccountsLeaveTheSummaryButKeepTheirNameInHistory() {
        ctx.store(income(ws, ctx.aurora.id(), "1000", day("2026-09-02"), "Salário"));
        ctx.store(income(ws, ctx.anasArchived.id(), "9999", day("2026-09-03"), "Conta antiga"));

        OverviewView view = overview.execute(ctx.anasWorkspaceId(), current());

        assertThat(view.accounts()).extracting(OverviewView.AccountSummary::name)
                .containsExactly("Banco Aurora", "Banco Norte");
        assertThat(view.summary().accountCount()).isEqualTo(2);
        assertThat(view.summary().totalBalance()).isEqualTo(brl("1000"));
        assertThat(view.cashFlow().income()).isEqualTo(brl("1000"));
        assertThat(view.recentTransactions().getFirst().account().name()).isEqualTo("Conta antiga");
    }

    // --- atividade recente ---

    @Test
    void recentTransactionsCarryEverythingTheUiNeedsNewestFirst() {
        Transaction purchase = ctx.store(expense(ws, ctx.aurora.id(), "86.40", day("2026-09-20"), "Bistrô Lume",
                CREATED_AT));
        ctx.store(income(ws, ctx.aurora.id(), "500", day("2026-09-21"), "Freelance"));
        ctx.store(transfer(ws, ctx.aurora.id(), ctx.norte.id(), "100"));
        ctx.store(refund(ws, ctx.norte.id(), "20", purchase));

        OverviewView view = overview.execute(ctx.anasWorkspaceId(), current());

        // transfer e refund no dia 22 (mesmo createdAt: desempate por id, decrescente), depois 21 e 20
        assertThat(view.recentTransactions()).hasSize(4);
        assertThat(view.recentTransactions().subList(2, 4)).extracting(OverviewView.RecentTransaction::description)
                .containsExactly("Freelance", "Bistrô Lume");

        OverviewView.RecentTransaction expense = view.recentTransactions().getLast();
        assertThat(expense.id()).isEqualTo(purchase.id().value());
        assertThat(expense.type()).isEqualTo(TransactionType.EXPENSE);
        assertThat(expense.flow()).isEqualTo(MovementFlow.OUTFLOW);
        assertThat(expense.status()).isEqualTo(TransactionStatus.POSTED);
        assertThat(expense.amount()).isEqualTo(brl("86.40"));
        assertThat(expense.occurredOn()).isEqualTo(day("2026-09-20"));
        assertThat(expense.account()).isEqualTo(new OverviewView.AccountRef(ctx.aurora.id().value(), "Banco Aurora"));
        assertThat(expense.destinationAccount()).isNull();

        OverviewView.RecentTransaction transfer = view.recentTransactions().stream()
                .filter(t -> t.type() == TransactionType.TRANSFER).findFirst().orElseThrow();
        assertThat(transfer.flow()).isEqualTo(MovementFlow.TRANSFER);
        assertThat(transfer.destinationAccount().name()).isEqualTo("Banco Norte");

        OverviewView.RecentTransaction refund = view.recentTransactions().stream()
                .filter(t -> t.type() == TransactionType.REFUND).findFirst().orElseThrow();
        assertThat(refund.flow()).isEqualTo(MovementFlow.INFLOW);
        assertThat(refund.refundOfTransactionId()).isEqualTo(purchase.id().value());
    }

    @Test
    void recentLimitDefaultsToFiveAndCanBeChanged() {
        for (int i = 1; i <= 8; i++) {
            ctx.store(income(ws, ctx.aurora.id(), "1", day("2026-09-" + String.format("%02d", i)), "Item " + i));
        }

        assertThat(overview.execute(ctx.anasWorkspaceId(), current()).recentTransactions())
                .extracting(OverviewView.RecentTransaction::description)
                .containsExactly("Item 8", "Item 7", "Item 6", "Item 5", "Item 4");
        assertThat(overview.execute(ctx.anasWorkspaceId(), new GetFinancialOverview.Query(null, null, null, 2))
                .recentTransactions()).hasSize(2);
        assertThat(overview.execute(ctx.anasWorkspaceId(), new GetFinancialOverview.Query(null, null, null, 20))
                .recentTransactions()).hasSize(8);
    }

    @Test
    void recentLimitIsValidated() {
        UUID id = ctx.anasWorkspaceId();

        assertInvalidValue(() -> overview.execute(id, new GetFinancialOverview.Query(null, null, null, 0)),
                "recentLimit", "OUT_OF_RANGE");
        assertInvalidValue(() -> overview.execute(id, new GetFinancialOverview.Query(null, null, null, 21)),
                "recentLimit", "OUT_OF_RANGE");
    }

    // --- isolamento e autorização ---

    @Test
    void eachWorkspaceSeesOnlyItsOwnNumbers() {
        populateSeptember();
        ctx.store(income(ctx.bobsWorkspace.id(), ctx.bobsAccount.id(), "42.00", day("2026-09-05"), "Pix do Bob"));

        OverviewView anas = overview.execute(ctx.anasWorkspaceId(), current());
        ctx.actAs(ctx.bob);
        OverviewView bobs = overview.execute(ctx.bobsWorkspace.id().value(), current());

        assertThat(bobs.workspaceId()).isEqualTo(ctx.bobsWorkspace.id().value());
        assertThat(bobs.summary().totalBalance()).isEqualTo(brl("42"));
        assertThat(bobs.cashFlow().income()).isEqualTo(brl("42"));
        assertThat(bobs.cashFlow().expense()).isEqualTo(brl("0"));
        assertThat(bobs.accounts()).extracting(OverviewView.AccountSummary::name).containsExactly("Conta do Bob");
        assertThat(bobs.recentTransactions()).extracting(OverviewView.RecentTransaction::description)
                .containsExactly("Pix do Bob");
        assertThat(anas.summary().totalBalance()).isEqualTo(brl("7627.52"));
        assertThat(anas.recentTransactions()).extracting(OverviewView.RecentTransaction::description)
                .doesNotContain("Pix do Bob");
    }

    @Test
    void aMemberOfAnotherWorkspaceCannotReadIt() {
        populateSeptember();
        ctx.actAs(ctx.bob);

        assertThatThrownBy(() -> overview.execute(ctx.anasWorkspaceId(), current()))
                .isInstanceOf(WorkspaceNotFoundException.class);
    }

    @Test
    void unknownWorkspaceIsIndistinguishableFromForeignOne() {
        assertThatThrownBy(() -> overview.execute(UUID.randomUUID(), current()))
                .isInstanceOf(WorkspaceNotFoundException.class);
    }

    @Test
    void authorizationRunsBeforeParameterValidation() {
        ctx.actAs(ctx.bob);

        assertThatThrownBy(() -> overview.execute(ctx.anasWorkspaceId(),
                new GetFinancialOverview.Query("INVALID", null, null, 999))).isInstanceOf(WorkspaceNotFoundException.class);
    }

    // --- helpers ---

    private void populateSeptember() {
        AccountId aurora = ctx.aurora.id();
        ctx.store(create(ws, TransactionType.ADJUSTMENT, aurora, null, AdjustmentDirection.INCREASE, "1200",
                day("2026-09-01"), "Saldo inicial", TransactionStatus.POSTED, null, CREATED_AT));
        ctx.store(income(ws, aurora, "6800", day("2026-09-05"), "Salário"));
        Transaction bistro = ctx.store(expense(ws, aurora, "86.40", day("2026-09-10"), "Bistrô Lume", CREATED_AT));
        ctx.store(expense(ws, aurora, "296.08", day("2026-09-12"), "Mercado", CREATED_AT));
        ctx.store(transfer(ws, aurora, ctx.norte.id(), "500"));
        ctx.store(refund(ws, aurora, "30", bistro));
        ctx.store(create(ws, TransactionType.ADJUSTMENT, aurora, null, AdjustmentDirection.DECREASE, "20",
                day("2026-09-14"), "Correção", TransactionStatus.POSTED, null, CREATED_AT));
    }

    private Account other(String name, boolean includedInTotal) {
        Account account = Account.create(AccountId.generate(CREATED_AT), ws, new AccountName(name), AccountType.OTHER,
                null, Workspace.DEFAULT_BASE_CURRENCY, includedInTotal, CREATED_AT.plusSeconds(1));
        ctx.accounts.store(account);
        return account;
    }

    private static GetFinancialOverview.Query current() {
        return new GetFinancialOverview.Query(null, null, null, null);
    }

    private static LocalDate day(String iso) {
        return LocalDate.parse(iso);
    }
}
