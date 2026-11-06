package com.smarthealthfinance.goals.application.dto;

import com.smarthealthfinance.goals.domain.valueobject.GoalMovement;
import com.smarthealthfinance.shared.domain.Money;

import java.time.LocalDate;
import java.util.List;

/**
 * Histórico do destino (ADR-0034 §2.3): o saldo na véspera do início do acompanhamento e os movimentos desde então,
 * mais recentes primeiro.
 *
 * @param openingDate    a véspera de {@code trackingStart}
 * @param openingBalance o saldo do destino nessa data ("Aporte inicial")
 */
public record GoalMovementPage(LocalDate openingDate, Money openingBalance, List<GoalMovement> movements, int page,
                               int pageSize, long totalElements) {

    public GoalMovementPage {
        movements = List.copyOf(movements);
    }
}
