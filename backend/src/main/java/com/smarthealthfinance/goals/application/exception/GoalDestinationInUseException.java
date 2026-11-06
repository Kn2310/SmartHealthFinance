package com.smarthealthfinance.goals.application.exception;

/** A conta já é destino de outro objetivo ativo ou pausado (ADR-0034 §2.1) → 409. */
public final class GoalDestinationInUseException extends RuntimeException {

    public GoalDestinationInUseException() {
        super("The account is already the destination of another open goal");
    }
}
