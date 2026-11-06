package com.smarthealthfinance.goals.domain.enums;

/** Tipo do objetivo (ADR-0034 §1). A reserva de emergência espelha o Reserve do Health (§3). */
public enum GoalType {
    EMERGENCY_RESERVE,
    PURCHASE,
    CUSTOM;

    public boolean isReserve() {
        return this == EMERGENCY_RESERVE;
    }
}
