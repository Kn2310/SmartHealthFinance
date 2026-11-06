package com.smarthealthfinance.goals.application.usecase;

import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.goals.application.dto.GoalView;
import com.smarthealthfinance.goals.application.service.GoalAccess;
import com.smarthealthfinance.goals.application.service.GoalCalendar;
import com.smarthealthfinance.goals.application.service.GoalDestinations;
import com.smarthealthfinance.goals.application.service.GoalEvents;
import com.smarthealthfinance.goals.application.service.GoalInputs;
import com.smarthealthfinance.goals.application.service.GoalReader;
import com.smarthealthfinance.goals.domain.model.Goal;
import com.smarthealthfinance.goals.domain.repository.GoalRepository;
import com.smarthealthfinance.goals.domain.valueobject.GoalDestination;
import com.smarthealthfinance.goals.domain.valueobject.GoalName;
import com.smarthealthfinance.goals.domain.valueobject.GoalPlan;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * "Editar objetivo" (ADR-0035): substitui nome, alvo, aporte planejado, prazo, origem dos aportes e destino. O tipo e
 * a origem do objetivo não mudam. Só emite {@code goal.plan-changed} quando algo mudou.
 */
@Service
public class UpdateGoal {

    private static final Logger log = LoggerFactory.getLogger(UpdateGoal.class);

    private final GoalAccess access;
    private final GoalRepository goals;
    private final GoalDestinations destinations;
    private final GoalReader reader;
    private final GoalEvents events;
    private final GoalCalendar calendar;
    private final Clock clock;

    public UpdateGoal(GoalAccess access, GoalRepository goals, GoalDestinations destinations, GoalReader reader,
                      GoalEvents events, GoalCalendar calendar, Clock clock) {
        this.access = access;
        this.goals = goals;
        this.destinations = destinations;
        this.reader = reader;
        this.events = events;
        this.calendar = calendar;
        this.clock = clock;
    }

    public record Command(String name, String targetAmount, String targetCurrency, String plannedMonthly,
                          String plannedCurrency, String deadline, UUID sourceAccountId, UUID destinationAccountId) {}

    @Transactional
    public GoalView execute(UUID workspaceId, UUID goalId, Command command) {
        WorkspaceId workspace = access.requireWorkspace(workspaceId);
        Goal goal = access.requireGoal(workspace, goalId);

        GoalName name = new GoalName(command.name());
        GoalPlan plan = new GoalPlan(GoalInputs.money("targetAmount", command.targetAmount(), command.targetCurrency()),
                GoalInputs.money("plannedMonthly", command.plannedMonthly(), command.plannedCurrency()),
                GoalInputs.month("deadline", command.deadline()));

        // Um destino que não mudou não é revalidado: a conta pode ter sido arquivada depois (ADR-0034 §2.1).
        UUID current = goal.destination().account().map(AccountId::value).orElse(null);
        GoalDestination destination = goal.type().isReserve() || !Objects.equals(current, command.destinationAccountId())
                ? destinations.resolve(workspace, goal.type(), command.destinationAccountId(), goal.id())
                : goal.destination();
        UUID currentSource = goal.sourceAccountId().map(AccountId::value).orElse(null);
        AccountId source = Objects.equals(currentSource, command.sourceAccountId())
                ? goal.sourceAccountId().orElse(null)
                : destinations.resolveSource(workspace, command.sourceAccountId());

        Set<String> changed = goal.update(name, plan, source, destination, calendar.today(), clock.instant());
        if (!changed.isEmpty()) {
            goals.save(goal);
            events.planChanged(goal, changed);
            log.info("Goal updated goalId={} workspaceId={} fields={}", goal.id(), workspace, changed);
        }
        return reader.view(workspace, goal);
    }
}
