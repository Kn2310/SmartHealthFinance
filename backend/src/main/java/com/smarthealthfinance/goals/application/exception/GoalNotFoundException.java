package com.smarthealthfinance.goals.application.exception;

/** Objetivo inexistente OU de outro Workspace: indistinguíveis de propósito (ADR-0003, ADR-0035). */
public final class GoalNotFoundException extends RuntimeException {

    public GoalNotFoundException() {
        super("Goal not found");
    }
}
