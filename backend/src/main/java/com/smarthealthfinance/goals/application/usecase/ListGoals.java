package com.smarthealthfinance.goals.application.usecase;

import com.smarthealthfinance.goals.application.dto.GoalListView;
import com.smarthealthfinance.goals.application.dto.GoalView;
import com.smarthealthfinance.goals.application.service.GoalAccess;
import com.smarthealthfinance.goals.application.service.GoalInputs;
import com.smarthealthfinance.goals.application.service.GoalReader;
import com.smarthealthfinance.goals.domain.enums.GoalStatus;
import com.smarthealthfinance.goals.domain.model.Goal;
import com.smarthealthfinance.goals.domain.repository.GoalRepository;
import com.smarthealthfinance.goals.domain.valueobject.GoalPlan;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * {@code GetGoals} (05.3, ADR-0035): sem filtro, os objetivos ativos e pausados; com {@code status}, só os daquele
 * status. Mais recentes primeiro. O resumo é sempre dos abertos, qualquer que seja o filtro.
 */
@Service
public class ListGoals {

    private final GoalAccess access;
    private final GoalRepository goals;
    private final GoalReader reader;

    public ListGoals(GoalAccess access, GoalRepository goals, GoalReader reader) {
        this.access = access;
        this.goals = goals;
        this.reader = reader;
    }

    /** @param status nulo = ativos e pausados */
    @Transactional(readOnly = true)
    public GoalListView execute(UUID workspaceId, String status) {
        WorkspaceId workspace = access.requireWorkspace(workspaceId);
        Predicate<GoalStatus> listed;
        if (status == null || status.isBlank()) {
            listed = GoalStatus::isOpen;
        } else {
            GoalStatus wanted = GoalInputs.status(status);
            listed = candidate -> candidate == wanted;
        }

        List<Goal> relevant = goals.findAllByWorkspace(workspace).stream()
                .filter(goal -> goal.status().isOpen() || listed.test(goal.status()))
                .toList();
        List<GoalView> views = reader.views(workspace, relevant);

        Money saved = Money.zero(GoalPlan.CURRENCY);
        Money planned = Money.zero(GoalPlan.CURRENCY);
        int active = 0;
        for (GoalView view : views) {
            if (!view.status().isOpen()) {
                continue;
            }
            saved = saved.plus(view.progress().saved());
            if (view.status() == GoalStatus.ACTIVE) {
                planned = planned.plus(view.plannedMonthly());
                active++;
            }
        }
        return new GoalListView(views.stream().filter(view -> listed.test(view.status())).toList(), saved, planned,
                active);
    }
}
