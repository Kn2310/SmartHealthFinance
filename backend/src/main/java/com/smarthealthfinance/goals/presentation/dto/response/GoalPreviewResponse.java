package com.smarthealthfinance.goals.presentation.dto.response;

import com.smarthealthfinance.goals.application.usecase.PreviewGoal;
import com.smarthealthfinance.shared.presentation.money.MoneyDto;

/**
 * Prévia do D-CreateGoal (ADR-0036). {@code alreadySaved} é fato (o saldo do destino hoje); {@code suggestedTarget}
 * é a referência do Health (6 × a despesa média, só na reserva); {@code simulation} é {@code SIMULATED}.
 *
 * @param target     o alvo usado na conta: o informado ou, na reserva sem alvo, o sugerido; nulo sem alvo
 * @param simulation nula sem alvo
 */
public record GoalPreviewResponse(MoneyDto alreadySaved, MoneyDto suggestedTarget, MoneyDto target,
                                  SimulationResponse simulation) {

    public static GoalPreviewResponse from(PreviewGoal.Preview preview) {
        return new GoalPreviewResponse(GoalResponse.money(preview.alreadySaved()),
                GoalResponse.money(preview.suggestedTarget()), GoalResponse.money(preview.target()),
                preview.simulation() == null ? null : SimulationResponse.from(preview.simulation()));
    }
}
