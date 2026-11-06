package com.smarthealthfinance.goals.domain.enums;

/** Situação do destino na leitura do progresso (ADR-0034 §2.1, §3). */
public enum DestinationState {
    OK,
    /** A conta dedicada foi arquivada: o saldo continua fato, mas o objetivo não recebe aportes. */
    ACCOUNT_ARCHIVED,
    /** Reserva sem nenhuma conta {@code SAVINGS} ativa: progresso zero. */
    NO_RESERVE_ACCOUNT
}
