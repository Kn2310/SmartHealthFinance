package com.smarthealthfinance.goals.presentation.dto.response;

import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.goals.application.dto.GoalMovementPage;
import com.smarthealthfinance.goals.domain.valueobject.GoalMovement;
import com.smarthealthfinance.shared.presentation.money.MoneyDto;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Histórico do destino do objetivo (ADR-0034 §2.3, ADR-0035): o saldo na véspera do início do acompanhamento e os
 * movimentos {@code POSTED} desde então, mais recentes primeiro. Tudo é fato.
 */
public record GoalContributionsResponse(GoalOpeningBalance openingBalance, List<GoalMovementDto> movements, int page,
                                        int pageSize, long totalElements) {

    /** O "Aporte inicial" do D-GoalDetail: o saldo do destino em {@code date}. */
    public record GoalOpeningBalance(LocalDate date, MoneyDto amount) {}

    /**
     * @param amount               sempre positivo; {@code direction} diz se entrou ou saiu
     * @param counterpartAccountId a outra conta da transferência; nulo para cartão ou movimento que não é transferência
     */
    public record GoalMovementDto(
            UUID transactionId,
            @Schema(allowableValues = {"CONTRIBUTION", "WITHDRAWAL", "OTHER"}) String kind,
            @Schema(allowableValues = {"IN", "OUT"}) String direction,
            LocalDate occurredOn,
            MoneyDto amount,
            UUID counterpartAccountId) {

        static GoalMovementDto from(GoalMovement movement) {
            return new GoalMovementDto(movement.transactionId().value(), movement.kind().name(),
                    movement.direction().name(), movement.occurredOn(), GoalResponse.money(movement.amount()),
                    movement.counterpart().map(AccountId::value).orElse(null));
        }
    }

    public static GoalContributionsResponse from(GoalMovementPage page) {
        return new GoalContributionsResponse(new GoalOpeningBalance(page.openingDate(),
                GoalResponse.money(page.openingBalance())),
                page.movements().stream().map(GoalMovementDto::from).toList(), page.page(), page.pageSize(),
                page.totalElements());
    }
}
