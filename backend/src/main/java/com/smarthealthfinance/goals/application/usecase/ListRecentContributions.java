package com.smarthealthfinance.goals.application.usecase;

import com.smarthealthfinance.goals.application.dto.RecentContributionView;
import com.smarthealthfinance.goals.application.service.GoalAccess;
import com.smarthealthfinance.goals.application.service.GoalReader;
import com.smarthealthfinance.goals.domain.enums.GoalMovementKind;
import com.smarthealthfinance.goals.domain.model.Goal;
import com.smarthealthfinance.goals.domain.repository.GoalRepository;
import com.smarthealthfinance.goals.domain.valueobject.GoalMovement;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.InvalidValueException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * "Aportes recentes" do D-Goals (ADR-0035): só os aportes ({@code CONTRIBUTION}) dos objetivos ativos e pausados,
 * mais recentes primeiro (data desc, id da transação desc).
 */
@Service
public class ListRecentContributions {

    public static final int DEFAULT_LIMIT = 5;
    public static final int MAX_LIMIT = 20;

    private static final Comparator<RecentContributionView> NEWEST_FIRST = Comparator
            .comparing((RecentContributionView view) -> view.contribution().occurredOn())
            .thenComparing(view -> view.contribution().transactionId().value())
            .reversed();

    private final GoalAccess access;
    private final GoalRepository goals;
    private final GoalReader reader;

    public ListRecentContributions(GoalAccess access, GoalRepository goals, GoalReader reader) {
        this.access = access;
        this.goals = goals;
        this.reader = reader;
    }

    @Transactional(readOnly = true)
    public List<RecentContributionView> execute(UUID workspaceId, Integer limit) {
        int size = limit == null ? DEFAULT_LIMIT : limit;
        if (size < 1 || size > MAX_LIMIT) {
            throw new InvalidValueException("limit", "OUT_OF_RANGE");
        }
        WorkspaceId workspace = access.requireWorkspace(workspaceId);

        List<RecentContributionView> all = new ArrayList<>();
        for (Goal goal : goals.findAllByWorkspace(workspace)) {
            if (!goal.status().isOpen()) {
                continue;
            }
            for (GoalMovement movement : reader.movements(workspace, goal, reader.destinationAccounts(workspace,
                    goal))) {
                if (movement.kind() == GoalMovementKind.CONTRIBUTION) {
                    all.add(new RecentContributionView(goal.id().value(), movement));
                }
            }
        }
        return all.stream().sorted(NEWEST_FIRST).limit(size).toList();
    }
}
