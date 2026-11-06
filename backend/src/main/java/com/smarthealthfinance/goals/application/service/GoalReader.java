package com.smarthealthfinance.goals.application.service;

import com.smarthealthfinance.accounts.domain.model.Account;
import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.goals.application.dto.GoalView;
import com.smarthealthfinance.goals.application.port.GoalBalanceSource;
import com.smarthealthfinance.goals.domain.enums.DestinationState;
import com.smarthealthfinance.goals.domain.model.Goal;
import com.smarthealthfinance.goals.domain.service.GoalMovementClassifier;
import com.smarthealthfinance.goals.domain.service.GoalProgressCalculator;
import com.smarthealthfinance.goals.domain.valueobject.GoalMovement;
import com.smarthealthfinance.goals.domain.valueobject.GoalPlan;
import com.smarthealthfinance.goals.domain.valueobject.GoalProgress;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.model.Transaction;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Lê o progresso (fato) e os movimentos dos destinos na data de negócio (ADR-0034 §2): uma consulta de saldos para
 * todos os objetivos da leitura. Um objetivo concluído mostra o valor congelado na conclusão (§2.5).
 */
@Service
public class GoalReader {

    private final GoalBalanceSource balances;
    private final GoalDestinations destinations;
    private final GoalCalendar calendar;

    public GoalReader(GoalBalanceSource balances, GoalDestinations destinations, GoalCalendar calendar) {
        this.balances = balances;
        this.destinations = destinations;
        this.calendar = calendar;
    }

    public List<GoalView> views(WorkspaceId workspace, List<Goal> goals) {
        if (goals.isEmpty()) {
            return List.of();
        }
        List<Account> accounts = destinations.workspaceAccounts(workspace);
        Set<AccountId> needed = new HashSet<>();
        for (Goal goal : goals) {
            if (goal.completedAmount().isEmpty()) {
                needed.addAll(destinations.accountsOf(goal, accounts));
            }
        }
        Map<AccountId, Money> figures = needed.isEmpty() ? Map.of()
                : balances.balances(workspace, needed, calendar.today(), GoalPlan.CURRENCY);

        List<GoalView> views = new ArrayList<>(goals.size());
        for (Goal goal : goals) {
            Money balance = goal.completedAmount()
                    .orElseGet(() -> sum(destinations.accountsOf(goal, accounts), figures));
            GoalProgress progress = GoalProgressCalculator.of(goal.plan().targetAmount(), balance);
            DestinationState state = destinations.stateOf(goal, accounts);
            views.add(GoalView.of(goal, progress, state));
        }
        return views;
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
        LocalDate today = calendar.today();
        if (accounts.isEmpty() || today.isBefore(goal.trackingStart())) {
            return List.of();
        }
        List<GoalMovement> movements = new ArrayList<>();
        for (Transaction transaction : balances.movements(workspace, accounts, goal.trackingStart(), today)) {
            Optional<GoalMovement> movement = GoalMovementClassifier.classify(transaction, accounts);
            movement.ifPresent(movements::add);
        }
        return movements;
    }

    /** Saldo do destino na véspera do início do acompanhamento. */
    public Money openingBalance(WorkspaceId workspace, Goal goal, Set<AccountId> accounts) {
        if (accounts.isEmpty()) {
            return Money.zero(GoalPlan.CURRENCY);
        }
        return sum(accounts, balances.balances(workspace, accounts, goal.trackingStart().minusDays(1),
                GoalPlan.CURRENCY));
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
