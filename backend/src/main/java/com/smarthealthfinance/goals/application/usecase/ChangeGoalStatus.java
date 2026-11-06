package com.smarthealthfinance.goals.application.usecase;

import com.smarthealthfinance.goals.application.dto.GoalView;
import com.smarthealthfinance.goals.application.service.GoalAccess;
import com.smarthealthfinance.goals.application.service.GoalEvents;
import com.smarthealthfinance.goals.application.service.GoalReader;
import com.smarthealthfinance.goals.domain.enums.GoalStatus;
import com.smarthealthfinance.goals.domain.enums.GoalStatusAction;
import com.smarthealthfinance.goals.domain.model.Goal;
import com.smarthealthfinance.goals.domain.repository.GoalRepository;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * Pausar, retomar, concluir e arquivar (ADR-0034 §1, ADR-0035), idempotentes. Concluir congela o progresso atual
 * (§2.5). Só emite {@code goal.status-changed} quando o status mudou.
 */
@Service
public class ChangeGoalStatus {

    private static final Logger log = LoggerFactory.getLogger(ChangeGoalStatus.class);

    private final GoalAccess access;
    private final GoalRepository goals;
    private final GoalReader reader;
    private final GoalEvents events;
    private final Clock clock;

    public ChangeGoalStatus(GoalAccess access, GoalRepository goals, GoalReader reader, GoalEvents events,
                            Clock clock) {
        this.access = access;
        this.goals = goals;
        this.reader = reader;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public GoalView execute(UUID workspaceId, UUID goalId, GoalStatusAction action) {
        WorkspaceId workspace = access.requireWorkspace(workspaceId);
        Goal goal = access.requireGoal(workspace, goalId);
        GoalStatus from = goal.status();
        Money progress = action == GoalStatusAction.COMPLETE && from.isOpen()
                ? reader.currentBalance(workspace, goal) : null;
        if (goal.apply(action, progress, clock.instant())) {
            goals.save(goal);
            events.statusChanged(goal, from);
            log.info("Goal status changed goalId={} workspaceId={} from={} to={}", goal.id(), workspace, from,
                    goal.status());
        }
        return reader.view(workspace, goal);
    }
}
