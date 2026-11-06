package com.smarthealthfinance.goals.application.usecase;

import com.smarthealthfinance.goals.application.dto.GoalView;
import com.smarthealthfinance.goals.application.service.GoalAccess;
import com.smarthealthfinance.goals.application.service.GoalCalendar;
import com.smarthealthfinance.goals.application.service.GoalEvents;
import com.smarthealthfinance.goals.application.service.GoalInputs;
import com.smarthealthfinance.goals.application.service.GoalReader;
import com.smarthealthfinance.goals.domain.model.Goal;
import com.smarthealthfinance.goals.domain.repository.GoalRepository;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Set;
import java.util.UUID;

/**
 * "Aplicar ao objetivo" (ADR-0034 §5, ADR-0035): troca só o aporte planejado e o prazo. Nunca toca em dado
 * financeiro; a confirmação explícita é da UI. Repetir o mesmo plano não grava nem emite evento.
 */
@Service
public class ApplyGoalPlan {

    private static final Logger log = LoggerFactory.getLogger(ApplyGoalPlan.class);

    private final GoalAccess access;
    private final GoalRepository goals;
    private final GoalReader reader;
    private final GoalEvents events;
    private final GoalCalendar calendar;
    private final Clock clock;

    public ApplyGoalPlan(GoalAccess access, GoalRepository goals, GoalReader reader, GoalEvents events,
                         GoalCalendar calendar, Clock clock) {
        this.access = access;
        this.goals = goals;
        this.reader = reader;
        this.events = events;
        this.calendar = calendar;
        this.clock = clock;
    }

    /** @param deadline {@code "2027-07"} ou nulo (sem prazo) */
    public record Command(String plannedMonthly, String plannedCurrency, String deadline) {}

    @Transactional
    public GoalView execute(UUID workspaceId, UUID goalId, Command command) {
        WorkspaceId workspace = access.requireWorkspace(workspaceId);
        Goal goal = access.requireGoal(workspace, goalId);
        Set<String> changed = goal.applyPlan(
                GoalInputs.money("plannedMonthly", command.plannedMonthly(), command.plannedCurrency()),
                GoalInputs.month("deadline", command.deadline()), calendar.today(), clock.instant());
        if (!changed.isEmpty()) {
            goals.save(goal);
            events.planChanged(goal, changed);
            log.info("Goal plan applied goalId={} workspaceId={} fields={}", goal.id(), workspace, changed);
        }
        return reader.view(workspace, goal);
    }
}
