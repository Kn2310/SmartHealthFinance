package com.smarthealthfinance.goals.domain.exception;

import com.smarthealthfinance.goals.domain.valueobject.GoalId;

/** A ação não é permitida no status atual do objetivo (ADR-0034 §1) → 409 {@code GOAL_STATUS_CONFLICT}. */
public class GoalStatusConflictException extends RuntimeException {

    public GoalStatusConflictException(GoalId goalId) {
        super("Goal status does not allow this action: " + goalId);
    }
}
