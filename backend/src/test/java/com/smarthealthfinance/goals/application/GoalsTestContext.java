package com.smarthealthfinance.goals.application;

import com.smarthealthfinance.accounts.application.InMemoryAccountRepository;
import com.smarthealthfinance.accounts.domain.enums.AccountType;
import com.smarthealthfinance.accounts.domain.model.Account;
import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.accounts.domain.valueobject.AccountName;
import com.smarthealthfinance.goals.application.port.GoalBalanceSource;
import com.smarthealthfinance.forecast.application.port.ForecastReader;
import com.smarthealthfinance.goals.application.service.GoalAccess;
import com.smarthealthfinance.goals.application.service.GoalAverages;
import com.smarthealthfinance.goals.application.service.GoalForecastContext;
import com.smarthealthfinance.goals.application.service.GoalSimulator;
import com.smarthealthfinance.goals.application.service.GoalCalendar;
import com.smarthealthfinance.goals.application.service.GoalDestinations;
import com.smarthealthfinance.goals.application.service.GoalEvents;
import com.smarthealthfinance.goals.application.service.GoalReader;
import com.smarthealthfinance.goals.application.usecase.ApplyGoalPlan;
import com.smarthealthfinance.goals.application.usecase.ChangeGoalStatus;
import com.smarthealthfinance.goals.application.usecase.CreateGoal;
import com.smarthealthfinance.goals.application.usecase.GetGoal;
import com.smarthealthfinance.goals.application.usecase.ListGoals;
import com.smarthealthfinance.goals.application.usecase.PreviewGoal;
import com.smarthealthfinance.goals.application.usecase.SimulateGoal;
import com.smarthealthfinance.goals.application.usecase.UpdateGoal;
import com.smarthealthfinance.goals.domain.model.Goal;
import com.smarthealthfinance.goals.domain.repository.GoalRepository;
import com.smarthealthfinance.goals.domain.valueobject.GoalId;
import com.smarthealthfinance.identity.application.InMemoryUserRepository;
import com.smarthealthfinance.identity.application.InMemoryWorkspaceRepository;
import com.smarthealthfinance.identity.application.dto.AuthenticatedIdentity;
import com.smarthealthfinance.identity.application.service.CurrentUserService;
import com.smarthealthfinance.identity.application.service.WorkspaceAccessGuard;
import com.smarthealthfinance.identity.domain.model.User;
import com.smarthealthfinance.identity.domain.model.Workspace;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.application.event.IntegrationEvent;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.model.Transaction;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.smarthealthfinance.identity.IdentityFixtures.activeUser;
import static com.smarthealthfinance.identity.IdentityFixtures.personalWorkspace;

/**
 * Ana e Bob com Workspaces próprios; "hoje" é terça, 22 set 2026 (09-dados-de-exemplo). Os saldos dos destinos vêm de
 * um fake da porta {@link GoalBalanceSource}.
 */
public final class GoalsTestContext {

    public static final Instant NOW = Instant.parse("2026-09-22T15:00:00Z");
    public static final Currency BRL = Currency.getInstance("BRL");

    public final InMemoryUserRepository users = new InMemoryUserRepository();
    public final InMemoryWorkspaceRepository workspaces = new InMemoryWorkspaceRepository();
    public final InMemoryAccountRepository accounts = new InMemoryAccountRepository();
    public final InMemoryGoalRepository goals = new InMemoryGoalRepository();
    public final FakeBalances balances = new FakeBalances();
    public final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    public final List<IntegrationEvent> outbox = new ArrayList<>();
    public final GoalEvents events = new GoalEvents(event -> {
        outbox.add(event);
        return UUID.randomUUID();
    });

    public final User ana = activeUser("sub-ana");
    public final User bob = activeUser("sub-bob");
    public final Workspace anasWorkspace = personalWorkspace(ana);
    public final Workspace bobsWorkspace = personalWorkspace(bob);

    private User actingUser = ana;

    public final GoalAccess access = new GoalAccess(new CurrentUserService(this::currentIdentity, users),
            new WorkspaceAccessGuard(workspaces), goals);
    public final GoalCalendar calendar = new GoalCalendar(clock, ZoneId.of("America/Sao_Paulo"));
    public final GoalDestinations destinations = new GoalDestinations(accounts, goals);
    /** Forecast sem snapshot: a previsão fica {@code NO_FORECAST} (só fatos), como num Workspace novo. */
    public final ForecastReader forecast = PortFakes.forecastWithoutSnapshot();
    /** Analytics com renda média de R$ 6.600 e despesa média de R$ 4.192 (09-dados, abr–set). */
    public final PortFakes.Analytics analytics = new PortFakes.Analytics();
    public final GoalAverages averages = new GoalAverages(analytics.port(), calendar);
    public final GoalReader reader = new GoalReader(balances, destinations, calendar, goals,
            new GoalForecastContext(forecast));
    public final GoalSimulator simulator = new GoalSimulator(averages, calendar);

    public final CreateGoal createGoal = new CreateGoal(access, goals, destinations, reader, events, calendar, clock);
    public final GetGoal getGoal = new GetGoal(access, reader);
    public final ListGoals listGoals = new ListGoals(access, goals, reader, averages);
    public final SimulateGoal simulateGoal = new SimulateGoal(access, reader, simulator);
    public final PreviewGoal previewGoal = new PreviewGoal(access, destinations, reader, averages, simulator);
    public final UpdateGoal updateGoal = new UpdateGoal(access, goals, destinations, reader, events, calendar, clock);
    public final ApplyGoalPlan applyPlan = new ApplyGoalPlan(access, goals, reader, events, calendar, clock);
    public final ChangeGoalStatus changeStatus = new ChangeGoalStatus(access, goals, reader, events, clock);

    public GoalsTestContext() {
        users.store(ana);
        users.store(bob);
        workspaces.store(anasWorkspace);
        workspaces.store(bobsWorkspace);
    }

    public void actAs(User user) {
        actingUser = user;
    }

    public UUID anasWorkspaceId() {
        return anasWorkspace.id().value();
    }

    public Account account(Workspace workspace, String name, AccountType type, String balance) {
        Account account = Account.create(AccountId.generate(NOW), workspace.id(), new AccountName(name), type, null,
                BRL, true, NOW);
        accounts.store(account);
        balances.set(account.id(), balance);
        return account;
    }

    private AuthenticatedIdentity currentIdentity() {
        return new AuthenticatedIdentity(actingUser.externalIdentity(), actingUser.email().value(), null, null);
    }

    /** Fake da porta de saldos: saldo fixo por conta; sem movimentos. */
    public static final class FakeBalances implements GoalBalanceSource {

        private final Map<AccountId, Money> balances = new HashMap<>();

        public void set(AccountId account, String amount) {
            balances.put(account, Money.of(amount, BRL));
        }

        @Override
        public Map<AccountId, Money> balances(WorkspaceId workspaceId, Set<AccountId> accounts, LocalDate asOf,
                                              Currency currency) {
            Map<AccountId, Money> result = new HashMap<>();
            for (AccountId account : accounts) {
                result.put(account, balances.getOrDefault(account, Money.zero(currency)));
            }
            return result;
        }

        @Override
        public List<Transaction> movements(WorkspaceId workspaceId, Set<AccountId> accounts, LocalDate from,
                                           LocalDate to) {
            return List.of();
        }
    }

    /** Imita o adapter: filtra por Workspace, devolve cópias (restore) e lista os mais recentes primeiro. */
    public static final class InMemoryGoalRepository implements GoalRepository {

        private final Map<GoalId, Goal> rows = new LinkedHashMap<>();

        @Override
        public Optional<Goal> findById(WorkspaceId workspaceId, GoalId goalId) {
            return Optional.ofNullable(rows.get(goalId)).filter(g -> g.workspaceId().equals(workspaceId))
                    .map(InMemoryGoalRepository::copy);
        }

        @Override
        public List<Goal> findAllByWorkspace(WorkspaceId workspaceId) {
            List<Goal> all = new ArrayList<>(rows.values().stream()
                    .filter(g -> g.workspaceId().equals(workspaceId)).map(InMemoryGoalRepository::copy).toList());
            java.util.Collections.reverse(all);
            return all;
        }

        @Override
        public Optional<Goal> findOpenByDestinationAccount(WorkspaceId workspaceId, AccountId accountId) {
            return findAllByWorkspace(workspaceId).stream()
                    .filter(g -> g.status().isOpen() && g.destination().account().filter(accountId::equals).isPresent())
                    .findFirst();
        }

        @Override
        public Optional<Goal> findOpenReserve(WorkspaceId workspaceId) {
            return findAllByWorkspace(workspaceId).stream()
                    .filter(g -> g.status().isOpen() && g.type().isReserve()).findFirst();
        }

        @Override
        public void add(Goal goal) {
            rows.put(goal.id(), copy(goal));
        }

        @Override
        public void save(Goal goal) {
            rows.put(goal.id(), copy(goal));
        }

        public int size() {
            return rows.size();
        }

        private static Goal copy(Goal g) {
            return Goal.restore(g.id(), g.workspaceId(), g.name(), g.type(), g.plan(), g.sourceAccountId().orElse(null),
                    g.destination(), g.origin(), g.status(), g.trackingStart(), g.completedAt().orElse(null),
                    g.completedAmount().orElse(null), g.createdAt(), g.updatedAt(), g.version());
        }
    }
}
