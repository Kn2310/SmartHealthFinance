package com.smarthealthfinance.goals.presentation.dto.response;

import com.smarthealthfinance.goals.application.dto.SimulationView;
import com.smarthealthfinance.goals.domain.valueobject.SimulationScenario;
import com.smarthealthfinance.shared.presentation.money.MoneyDto;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Simulação, sempre {@code SIMULATED} e nunca gravada (ADR-0034 §5, ADR-0036). Não altera o objetivo nem dado
 * financeiro; "Aplicar ao objetivo" é o {@code PUT /goals/{goalId}/plan}.
 *
 * @param remaining     quanto falta para o alvo
 * @param start         {@code "2026-10"}: o mês do primeiro aporte
 * @param averageIncome a renda média usada no peso na renda; nula sem renda
 * @param assumptions   premissas exibidas junto do resultado
 */
public record SimulationResponse(
        @Schema(allowableValues = {"SIMULATED"}) String basis,
        MoneyDto remaining,
        String start,
        MoneyDto averageIncome,
        @Schema(allowableValues = {"EQUAL_MONTHLY_CONTRIBUTIONS", "NO_YIELD"}) List<String> assumptions,
        List<SimulationScenarioDto> scenarios) {

    public static final List<String> ASSUMPTIONS = List.of("EQUAL_MONTHLY_CONTRIBUTIONS", "NO_YIELD");

    /**
     * @param completion                 {@code "2027-07"}; nulo com aporte zero, alvo já alcançado ou {@code tooFar}
     * @param lastContribution           o último aporte (menor quando sobra resto); nulo sem conclusão
     * @param deadline                   o prazo considerado e a comparação; nulo sem prazo
     * @param requiredMonthlyForDeadline menor aporte que cumpre o prazo; nulo sem prazo ou com prazo antes do início
     * @param incomeShare                aporte ÷ renda média, % inteiro; nulo sem renda
     * @param reduction                  a redução de gasto somada ao aporte, se houver (nenhum gasto muda)
     */
    public record SimulationScenarioDto(
            MoneyDto monthlyAmount,
            String completion,
            int contributions,
            String firstContribution,
            SimulationLastContribution lastContribution,
            SimulationDeadline deadline,
            MoneyDto requiredMonthlyForDeadline,
            Integer incomeShare,
            boolean tooFar,
            SimulationReduction reduction) {}

    public record SimulationLastContribution(String month, MoneyDto amount) {}

    /** @param monthsFromDeadline conclusão − prazo em meses (negativo = antes); nulo sem conclusão */
    public record SimulationDeadline(String month, Boolean within, Integer monthsFromDeadline) {}

    public record SimulationReduction(String categoryCode, MoneyDto amount) {}

    public static SimulationResponse from(SimulationView view) {
        return new SimulationResponse("SIMULATED", GoalResponse.money(view.remaining()), view.start().toString(),
                GoalResponse.money(view.averageIncome()), ASSUMPTIONS,
                view.scenarios().stream().map(SimulationResponse::scenario).toList());
    }

    private static SimulationScenarioDto scenario(SimulationView.ScenarioView view) {
        SimulationScenario s = view.result();
        return new SimulationScenarioDto(GoalResponse.money(s.monthlyAmount()),
                s.completion() == null ? null : s.completion().toString(), s.contributions(),
                s.firstContribution().toString(),
                s.completion() == null ? null
                        : new SimulationLastContribution(s.completion().toString(),
                                GoalResponse.money(s.lastContributionAmount())),
                s.deadline() == null ? null
                        : new SimulationDeadline(s.deadline().toString(), s.withinDeadline(), s.monthsFromDeadline()),
                GoalResponse.money(s.requiredMonthlyForDeadline()), s.incomeShare(), s.tooFar(),
                view.reductionAmount() == null ? null
                        : new SimulationReduction(view.reductionCategory(), GoalResponse.money(view.reductionAmount())));
    }
}
