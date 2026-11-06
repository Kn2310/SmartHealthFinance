package com.smarthealthfinance.goals.presentation.dto.response;

import com.smarthealthfinance.goals.application.dto.GoalView;
import com.smarthealthfinance.goals.domain.valueobject.GoalProgress;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.shared.presentation.money.MoneyDto;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Objetivo com o progresso, sempre fato (ADR-0034 §2, ADR-0035). A previsão (Estimado) entra na Parte C como campo
 * aditivo.
 *
 * @param deadline        {@code "2027-07"} ou nulo (sem prazo)
 * @param completedAmount o progresso congelado na conclusão (§2.5)
 */
public record GoalResponse(
        UUID id,
        String name,
        @Schema(allowableValues = {"EMERGENCY_RESERVE", "PURCHASE", "CUSTOM"}) String type,
        @Schema(allowableValues = {"ACTIVE", "PAUSED", "COMPLETED", "ARCHIVED"}) String status,
        GoalOriginDto origin,
        MoneyDto targetAmount,
        MoneyDto plannedMonthly,
        String deadline,
        UUID sourceAccountId,
        GoalDestinationDto destination,
        LocalDate trackingStart,
        Instant createdAt,
        Instant completedAt,
        MoneyDto completedAmount,
        GoalProgressDto progress) {

    public record GoalOriginDto(@Schema(allowableValues = {"MANUAL", "INSIGHT", "HEALTH"}) String kind, UUID insightId) {}

    /** {@code ACCOUNT}: a conta dedicada; {@code RESERVE}: as contas {@code SAVINGS} ativas (sem {@code accountId}). */
    public record GoalDestinationDto(@Schema(allowableValues = {"ACCOUNT", "RESERVE"}) String kind, UUID accountId) {}

    /**
     * @param saved              o guardado (saldo do destino, nunca negativo, ou o valor da conclusão)
     * @param percent            0 a 100, teto 99 até atingir o alvo
     * @param destinationBalance o saldo real do destino, que pode ser negativo
     */
    public record GoalProgressDto(
            @Schema(allowableValues = {"FACT"}) String basis,
            MoneyDto saved,
            int percent,
            MoneyDto remaining,
            boolean reached,
            MoneyDto destinationBalance,
            @Schema(allowableValues = {"OK", "ACCOUNT_ARCHIVED", "NO_RESERVE_ACCOUNT"}) String destinationState) {}

    public static GoalResponse from(GoalView view) {
        GoalProgress progress = view.progress();
        return new GoalResponse(view.id(), view.name(), view.type().name(), view.status().name(),
                new GoalOriginDto(view.originKind().name(), view.originInsightId()), money(view.targetAmount()),
                money(view.plannedMonthly()), view.deadline() == null ? null : view.deadline().toString(),
                view.sourceAccountId(), new GoalDestinationDto(view.destinationKind().name(), view.destinationAccountId()),
                view.trackingStart(), view.createdAt(), view.completedAt(), money(view.completedAmount()),
                new GoalProgressDto("FACT", money(progress.saved()), progress.percent(), money(progress.remaining()),
                        progress.reached(), money(progress.destinationBalance()), view.destinationState().name()));
    }

    static MoneyDto money(Money money) {
        return money == null ? null : new MoneyDto(money.toPlainString(), money.currency().getCurrencyCode());
    }
}
