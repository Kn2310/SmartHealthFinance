package com.smarthealthfinance.goals.application.usecase;

import com.smarthealthfinance.accounts.domain.model.Account;
import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.goals.application.dto.SimulationView;
import com.smarthealthfinance.goals.application.service.GoalAccess;
import com.smarthealthfinance.goals.application.service.GoalAverages;
import com.smarthealthfinance.goals.application.service.GoalDestinations;
import com.smarthealthfinance.goals.application.service.GoalInputs;
import com.smarthealthfinance.goals.application.service.GoalReader;
import com.smarthealthfinance.goals.application.service.GoalSimulator;
import com.smarthealthfinance.goals.domain.enums.GoalType;
import com.smarthealthfinance.goals.domain.service.MonthlyAverages;
import com.smarthealthfinance.goals.domain.service.GoalProgressCalculator;
import com.smarthealthfinance.goals.domain.valueobject.GoalPlan;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.InvalidValueException;
import com.smarthealthfinance.shared.domain.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Prévia ao vivo do D-CreateGoal (ADR-0036), sempre {@code SIMULATED} e sem gravar nada: o já guardado (saldo do
 * destino), o alvo sugerido da reserva (6 × a despesa média, janela do Health) e o cenário do plano digitado. Sem
 * alvo (ainda não digitado), a reserva usa o alvo sugerido. O destino só precisa existir no Workspace: as regras de
 * criação (exclusividade, {@code SAVINGS}) são do {@code POST /goals}.
 */
@Service
public class PreviewGoal {

    private final GoalAccess access;
    private final GoalDestinations destinations;
    private final GoalReader reader;
    private final GoalAverages averages;
    private final GoalSimulator simulator;

    public PreviewGoal(GoalAccess access, GoalDestinations destinations, GoalReader reader, GoalAverages averages,
                       GoalSimulator simulator) {
        this.access = access;
        this.destinations = destinations;
        this.reader = reader;
        this.averages = averages;
        this.simulator = simulator;
    }

    /** @param targetAmount nulo = ainda não informado */
    public record Command(String type, String targetAmount, String plannedMonthly, String deadline,
                          UUID destinationAccountId) {}

    /**
     * @param alreadySaved    o saldo do destino hoje (fato), nunca negativo
     * @param suggestedTarget só na reserva: 6 × a despesa média; nulo sem despesa
     * @param target          o alvo usado na conta (o informado, ou o sugerido na reserva); nulo sem alvo
     * @param simulation      nulo sem alvo
     */
    public record Preview(Money alreadySaved, Money suggestedTarget, Money target, SimulationView simulation) {}

    @Transactional(readOnly = true)
    public Preview execute(UUID workspaceId, Command command) {
        WorkspaceId workspace = access.requireWorkspace(workspaceId);
        GoalType type = GoalInputs.type(command.type());
        Money planned = GoalInputs.money("plannedMonthly", command.plannedMonthly(),
                GoalPlan.CURRENCY.getCurrencyCode());
        if (planned.amount().signum() < 0) {
            throw new InvalidValueException("plannedMonthly", "NEGATIVE");
        }
        Money informed = command.targetAmount() == null ? null
                : GoalInputs.money("targetAmount", command.targetAmount(), GoalPlan.CURRENCY.getCurrencyCode());
        if (informed != null && !informed.isPositive()) {
            throw new InvalidValueException("targetAmount", "MUST_BE_POSITIVE");
        }

        Set<AccountId> accounts = new LinkedHashSet<>();
        if (type.isReserve()) {
            if (command.destinationAccountId() != null) {
                throw new InvalidValueException("destinationAccountId", "NOT_ALLOWED");
            }
            for (Account account : destinations.workspaceAccounts(workspace)) {
                if (GoalDestinations.isReserveAccount(account)) {
                    accounts.add(account.id());
                }
            }
        } else if (command.destinationAccountId() != null) {
            accounts.add(destinations.requireActive(workspace, command.destinationAccountId()).id());
        }
        Money balance = reader.balanceAt(workspace, accounts, reader.today());
        Money saved = balance.amount().signum() < 0 ? Money.zero(GoalPlan.CURRENCY) : balance;

        MonthlyAverages.Averages monthly = type.isReserve() ? averages.load(workspace) : null;
        Money suggested = monthly == null ? null : monthly.reserveTarget().orElse(null);
        Money target = informed != null ? informed : suggested;
        if (target == null) {
            return new Preview(saved, suggested, null, null);
        }
        Money remaining = GoalProgressCalculator.of(target, saved).remaining();
        SimulationView simulation = simulator.simulate(workspace, remaining, planned,
                GoalInputs.month("deadline", command.deadline()),
                List.of(new GoalSimulator.Scenario(null, null, null, null)));
        return new Preview(saved, suggested, target, simulation);
    }
}
