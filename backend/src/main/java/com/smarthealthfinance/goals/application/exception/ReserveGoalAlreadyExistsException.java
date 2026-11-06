package com.smarthealthfinance.goals.application.exception;

/** Já existe uma reserva de emergência ativa ou pausada no Workspace (ADR-0034 §3) → 409. */
public final class ReserveGoalAlreadyExistsException extends RuntimeException {

    public ReserveGoalAlreadyExistsException() {
        super("An open emergency reserve goal already exists");
    }
}
