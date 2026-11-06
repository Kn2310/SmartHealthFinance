package com.smarthealthfinance.goals.application.usecase;

import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.goals.application.dto.GoalView;
import com.smarthealthfinance.goals.application.service.GoalAccess;
import com.smarthealthfinance.goals.application.service.GoalCalendar;
import com.smarthealthfinance.goals.application.service.GoalDestinations;
import com.smarthealthfinance.goals.application.service.GoalEvents;
import com.smarthealthfinance.goals.application.service.GoalInputs;
import com.smarthealthfinance.goals.application.service.GoalReader;
import com.smarthealthfinance.goals.domain.enums.GoalType;
import com.smarthealthfinance.goals.domain.model.Goal;
import com.smarthealthfinance.goals.domain.repository.GoalRepository;
import com.smarthealthfinance.goals.domain.valueobject.GoalDestination;
import com.smarthealthfinance.goals.domain.valueobject.GoalId;
import com.smarthealthfinance.goals.domain.valueobject.GoalName;
import com.smarthealthfinance.goals.domain.valueobject.GoalOrigin;
import com.smarthealthfinance.goals.domain.valueobject.GoalPlan;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

/** {@code CreateGoal} (05.3, ADR-0034 §1, ADR-0035): valida plano, destino e origem e grava o objetivo ativo. */
@Service
public class CreateGoal {

    private static final Logger log = LoggerFactory.getLogger(CreateGoal.class);

    private final GoalAccess access;
    private final GoalRepository goals;
    private final GoalDestinations destinations;
    private final GoalReader reader;
    private final GoalEvents events;
    private final GoalCalendar calendar;
    private final Clock clock;

    public CreateGoal(GoalAccess access, GoalRepository goals, GoalDestinations destinations, GoalReader reader,
                      GoalEvents events, GoalCalendar calendar, Clock clock) {
        this.access = access;
        this.goals = goals;
        this.destinations = destinations;
        this.reader = reader;
        this.events = events;
        this.calendar = calendar;
        this.clock = clock;
    }

    /** @param deadline {@code "2027-07"} ou nulo */
    public record Command(String name, String type, String targetAmount, String targetCurrency,
                          String plannedMonthly, String plannedCurrency, String deadline, UUID sourceAccountId,
                          UUID destinationAccountId, String originKind, UUID originInsightId) {}

    @Transactional
    public GoalView execute(UUID workspaceId, Command command) {
        WorkspaceId workspace = access.requireWorkspace(workspaceId);
        Instant now = clock.instant();
        LocalDate today = calendar.today();

        GoalName name = new GoalName(command.name());
        GoalType type = GoalInputs.type(command.type());
        GoalPlan plan = new GoalPlan(GoalInputs.money("targetAmount", command.targetAmount(), command.targetCurrency()),
                GoalInputs.money("plannedMonthly", command.plannedMonthly(), command.plannedCurrency()),
                GoalInputs.month("deadline", command.deadline()));
        GoalOrigin origin = new GoalOrigin(GoalInputs.origin(command.originKind()), command.originInsightId());
        // Antes das consultas de destino: entrada inválida responde 400 sem tocar no banco.
        plan.requireDeadlineAfter(YearMonth.from(today));

        GoalDestination destination = destinations.resolve(workspace, type, command.destinationAccountId(), null);
        AccountId source = destinations.resolveSource(workspace, command.sourceAccountId());

        Goal goal = Goal.create(GoalId.generate(now), workspace, name, type, plan, source, destination, origin, today,
                now);
        goals.add(goal);
        events.created(goal);
        log.info("Goal created goalId={} workspaceId={} type={}", goal.id(), workspace, type);
        return reader.view(workspace, goal);
    }
}
