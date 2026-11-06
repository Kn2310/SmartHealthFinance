package com.smarthealthfinance.goals.application.usecase;

import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.goals.application.dto.GoalMovementPage;
import com.smarthealthfinance.goals.application.service.GoalAccess;
import com.smarthealthfinance.goals.application.service.GoalReader;
import com.smarthealthfinance.goals.domain.model.Goal;
import com.smarthealthfinance.goals.domain.valueobject.GoalMovement;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.InvalidValueException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Histórico de aportes do objetivo (ADR-0034 §2.3, ADR-0035): o saldo na véspera do início do acompanhamento e os
 * movimentos {@code POSTED} do destino desde então (aportes, resgates e outros), mais recentes primeiro, paginados.
 */
@Service
public class ListGoalContributions {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 50;

    private final GoalAccess access;
    private final GoalReader reader;

    public ListGoalContributions(GoalAccess access, GoalReader reader) {
        this.access = access;
        this.reader = reader;
    }

    /** @param page 0-based; {@code pageSize} de 1 a 50 (nulos = 0 e 20) */
    @Transactional(readOnly = true)
    public GoalMovementPage execute(UUID workspaceId, UUID goalId, Integer page, Integer pageSize) {
        int number = page == null ? 0 : page;
        int size = pageSize == null ? DEFAULT_PAGE_SIZE : pageSize;
        if (number < 0) {
            throw new InvalidValueException("page", "OUT_OF_RANGE");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidValueException("pageSize", "OUT_OF_RANGE");
        }

        WorkspaceId workspace = access.requireWorkspace(workspaceId);
        Goal goal = access.requireGoal(workspace, goalId);
        Set<AccountId> accounts = reader.destinationAccounts(workspace, goal);
        List<GoalMovement> movements = reader.movements(workspace, goal, accounts);

        long from = (long) number * size;
        List<GoalMovement> slice = from >= movements.size() ? List.of()
                : movements.subList((int) from, (int) Math.min(movements.size(), from + size));
        return new GoalMovementPage(goal.trackingStart().minusDays(1), reader.openingBalance(workspace, goal, accounts),
                slice, number, size, movements.size());
    }
}
