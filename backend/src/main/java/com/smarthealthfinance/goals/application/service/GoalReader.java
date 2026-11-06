package com.smarthealthfinance.goals.application.service;

import com.smarthealthfinance.accounts.domain.model.Account;
import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.goals.application.dto.GoalView;
import com.smarthealthfinance.goals.application.port.GoalBalanceSource;
import com.smarthealthfinance.goals.domain.enums.DestinationState;
import com.smarthealthfinance.goals.domain.enums.GoalMovementKind;
import com.smarthealthfinance.goals.domain.enums.GoalStatus;
import com.smarthealthfinance.goals.domain.model.Goal;
import com.smarthealthfinance.goals.domain.repository.GoalRepository;
import com.smarthealthfinance.goals.domain.service.GoalForecaster;
import com.smarthealthfinance.goals.domain.service.GoalMovementClassifier;
import com.smarthealthfinance.goals.domain.service.GoalProgressCalculator;
import com.smarthealthfinance.goals.domain.valueobject.GoalForecast;
import com.smarthealthfinance.goals.domain.valueobject.GoalMovement;
import com.smarthealthfinance.goals.domain.valueobject.GoalPlan;
import com.smarthealthfinance.goals.domain.valueobject.GoalProgress;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.model.Transaction;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Lê o progresso (fato) e os movimentos dos destinos na data de negócio (ADR-0034 §2) e monta a previsão (Estimado)
 * sobre o Forecast (§4, ADR-0036): uma consulta de saldos para todos os objetivos da leitura. Um objetivo concluído
 * mostra o valor congelado na conclusão (§2.5).
 */
@Service
public class GoalReader {

    private final GoalBalanceSource balances;
    private final GoalDestinations destinations;
    private final GoalCalendar calendar;
    private final GoalRepository goals;
    private final GoalForecastContext forecast;

    public GoalReader(GoalBalanceSource balances, GoalDestinations destinations, GoalCalendar calendar,
                      GoalRepository goals, GoalForecastContext forecast) {
        this.balances = balances;
        this.destinations = destinations;
        this.calendar = calendar;
        this.goals = goals;
        this.forecast = forecast;
    }

    /** Leitura de um conjunto de objetivos, com o contexto do Forecast do Workspace. */
    public record Read(List<GoalView> views, GoalForecastContext.Snapshot forecast) {}

    public Read read(WorkspaceId workspace, List<Goal> selected) {
        GoalForecastContext.Snapshot snapshot = forecast.load(workspace);
        if (selected.isEmpty()) {
            return new Read(List.of(), snapshot);
        }
        List<Account> accounts = destinations.workspaceAccounts(workspace);
        LocalDate today = calendar.today();
        YearMonth currentMonth = YearMonth.from(today);

        Set<AccountId> needed = new HashSet<>();
        for (Goal goal : selected) {
            if (goal.completedAmount().isEmpty()) {
                needed.addAll(destinations.accountsOf(goal, accounts));
            }
        }
        Map<AccountId, Money> figures = needed.isEmpty() ? Map.of()
                : balances.balances(workspace, needed, today, GoalPlan.CURRENCY);
        Money plannedOfActive = plannedOfActive(workspace);

        List<GoalView> views = new ArrayList<>(selected.size());
        for (Goal goal : selected) {
            Set<AccountId> destination = destinations.accountsOf(goal, accounts);
            Money balance = goal.completedAmount().orElseGet(() -> sum(destination, figures));
            GoalProgress progress = GoalProgressCalculator.of(goal.plan().targetAmount(), balance);
            DestinationState state = destinations.stateOf(goal, accounts);
            List<Money> nets = goal.status() == GoalStatus.ACTIVE && snapshot.ready() && !progress.reached()
                    ? closedMonthlyNets(workspace, goal, destination, currentMonth) : List.of();
            GoalForecast prediction = GoalForecaster.forecast(goal.status(), progress.remaining(), progress.reached(),
                    goal.plan().plannedMonthly(), goal.plan().deadline(), currentMonth, snapshot.ready(), nets,
                    snapshot.capacity(), plannedOfActive);
            views.add(GoalView.of(goal, progress, state, prediction, snapshot.freshness()));
        }
        return new Read(views, snapshot);
    }

    public List<GoalView> views(WorkspaceId workspace, List<Goal> selected) {
        return read(workspace, selected).views();
    }

    public GoalView view(WorkspaceId workspace, Goal goal) {
        return views(workspace, List.of(goal)).getFirst();
    }

    /** O progresso atual (fato), para congelar na conclusão. */
    public Money currentBalance(WorkspaceId workspace, Goal goal) {
        Set<AccountId> accounts = destinations.accountsOf(goal, destinations.workspaceAccounts(workspace));
        if (accounts.isEmpty()) {
            return Money.zero(GoalPlan.CURRENCY);
        }
        Money balance = sum(accounts, balances.balances(workspace, accounts, calendar.today(), GoalPlan.CURRENCY));
        return balance.amount().signum() < 0 ? Money.zero(GoalPlan.CURRENCY) : balance;
    }

    /** As contas do destino do objetivo hoje. */
    public Set<AccountId> destinationAccounts(WorkspaceId workspace, Goal goal) {
        return destinations.accountsOf(goal, destinations.workspaceAccounts(workspace));
    }

    /** Movimentos classificados do destino, de {@code trackingStart} até hoje, mais recentes primeiro. */
    public List<GoalMovement> movements(WorkspaceId workspace, Goal goal, Set<AccountId> accounts) {
        return movements(workspace, goal, accounts, calendar.today());
    }

    /** Saldo do destino na véspera do início do acompanhamento. */
    public Money openingBalance(WorkspaceId workspace, Goal goal, Set<AccountId> accounts) {
        return balanceAt(workspace, accounts, goal.trackingStart().minusDays(1));
    }

    /** Saldo somado das contas do destino numa data (inclusive). */
    public Money balanceAt(WorkspaceId workspace, Set<AccountId> accounts, LocalDate date) {
        if (accounts.isEmpty()) {
            return Money.zero(GoalPlan.CURRENCY);
        }
        return sum(accounts, balances.balances(workspace, accounts, date, GoalPlan.CURRENCY));
    }

    public LocalDate today() {
        return calendar.today();
    }

    /**
     * Aporte líquido (aportes − resgates) de cada mês fechado desde o mês do início do acompanhamento, do mais antigo
     * ao mais recente (ADR-0034 §4). Outros movimentos não contam.
     */
    List<Money> closedMonthlyNets(WorkspaceId workspace, Goal goal, Set<AccountId> destination,
                                  YearMonth currentMonth) {
        YearMonth first = YearMonth.from(goal.trackingStart());
        YearMonth lastClosed = currentMonth.minusMonths(1);
        if (destination.isEmpty() || lastClosed.isBefore(first)) {
            return List.of();
        }
        Map<YearMonth, Money> byMonth = new LinkedHashMap<>();
        for (YearMonth month = first; !month.isAfter(lastClosed); month = month.plusMonths(1)) {
            byMonth.put(month, Money.zero(GoalPlan.CURRENCY));
        }
        for (GoalMovement movement : movements(workspace, goal, destination, lastClosed.atEndOfMonth())) {
            YearMonth month = YearMonth.from(movement.occurredOn());
            Money current = byMonth.get(month);
            if (current == null) {
                continue;
            }
            if (movement.kind() == GoalMovementKind.CONTRIBUTION) {
                byMonth.put(month, current.plus(movement.amount()));
            } else if (movement.kind() == GoalMovementKind.WITHDRAWAL) {
                byMonth.put(month, current.minus(movement.amount()));
            }
        }
        return List.copyOf(byMonth.values());
    }

    private List<GoalMovement> movements(WorkspaceId workspace, Goal goal, Set<AccountId> accounts, LocalDate to) {
        if (accounts.isEmpty() || to.isBefore(goal.trackingStart())) {
            return List.of();
        }
        List<GoalMovement> movements = new ArrayList<>();
        for (Transaction transaction : balances.movements(workspace, accounts, goal.trackingStart(), to)) {
            Optional<GoalMovement> movement = GoalMovementClassifier.classify(transaction, accounts);
            movement.ifPresent(movements::add);
        }
        return movements;
    }

    private Money plannedOfActive(WorkspaceId workspace) {
        Money total = Money.zero(GoalPlan.CURRENCY);
        for (Goal goal : goals.findAllByWorkspace(workspace)) {
            if (goal.status() == GoalStatus.ACTIVE) {
                total = total.plus(goal.plan().plannedMonthly());
            }
        }
        return total;
    }

    private static Money sum(Set<AccountId> accounts, Map<AccountId, Money> figures) {
        Money total = Money.zero(GoalPlan.CURRENCY);
        for (AccountId account : accounts) {
            Money balance = figures.get(account);
            if (balance != null) {
                total = total.plus(balance);
            }
        }
        return total;
    }
}
