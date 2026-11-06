package com.smarthealthfinance.goals.application.dto;

import com.smarthealthfinance.goals.domain.enums.DestinationKind;
import com.smarthealthfinance.goals.domain.enums.DestinationState;
import com.smarthealthfinance.goals.domain.enums.GoalForecastFreshness;
import com.smarthealthfinance.goals.domain.enums.GoalOriginKind;
import com.smarthealthfinance.goals.domain.enums.GoalStatus;
import com.smarthealthfinance.goals.domain.enums.GoalType;
import com.smarthealthfinance.goals.domain.model.Goal;
import com.smarthealthfinance.goals.domain.valueobject.GoalForecast;
import com.smarthealthfinance.goals.domain.valueobject.GoalProgress;
import com.smarthealthfinance.shared.domain.Money;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

/**
 * Objetivo com o progresso (fato) lido do destino e a previsão (Estimado) sobre o Forecast, na data de negócio
 * (ADR-0034 §2, §4; ADR-0035; ADR-0036).
 */
public record GoalView(UUID id, String name, GoalType type, GoalStatus status, GoalOriginKind originKind,
                       UUID originInsightId, Money targetAmount, Money plannedMonthly, YearMonth deadline,
                       UUID sourceAccountId, DestinationKind destinationKind, UUID destinationAccountId,
                       LocalDate trackingStart, Instant createdAt, Instant completedAt, Money completedAmount,
                       GoalProgress progress, DestinationState destinationState, GoalForecast forecast,
                       GoalForecastFreshness forecastFreshness) {

    public static GoalView of(Goal goal, GoalProgress progress, DestinationState state, GoalForecast forecast,
                              GoalForecastFreshness freshness) {
        return new GoalView(goal.id().value(), goal.name().value(), goal.type(), goal.status(),
                goal.origin().kind(), goal.origin().insight().orElse(null), goal.plan().targetAmount(),
                goal.plan().plannedMonthly(), goal.plan().deadline(),
                goal.sourceAccountId().map(a -> a.value()).orElse(null), goal.destination().kind(),
                goal.destination().account().map(a -> a.value()).orElse(null), goal.trackingStart(),
                goal.createdAt(), goal.completedAt().orElse(null), goal.completedAmount().orElse(null), progress,
                state, forecast, freshness);
    }
}
