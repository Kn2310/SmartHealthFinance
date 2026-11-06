package com.smarthealthfinance.goals.domain.enums;

/**
 * Tipo do destino do objetivo (ADR-0034 §2.1, §9). {@code ACCOUNT}: uma conta dedicada; {@code RESERVE}: as contas
 * {@code SAVINGS} ativas, só para a reserva de emergência. {@code INVESTMENT_POSITION} fica para o Open Finance.
 */
public enum DestinationKind {
    ACCOUNT,
    RESERVE
}
