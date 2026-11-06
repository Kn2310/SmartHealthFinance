package com.smarthealthfinance.goals.domain.valueobject;

import com.smarthealthfinance.shared.domain.Money;

import java.time.YearMonth;
import java.util.Objects;
import java.util.Optional;

/**
 * Resultado de um cenário do {@code SimulationEngine} (ADR-0034 §5), sempre {@code SIMULATED}.
 *
 * @param monthlyAmount             o aporte mensal simulado
 * @param completion                o mês em que o alvo é alcançado; vazio quando o aporte é zero ou passa do teto
 * @param contributions             quantos aportes faltam (zero se já alcançado)
 * @param firstContribution         o mês do primeiro aporte (o mês seguinte ao corrente)
 * @param lastContributionAmount    o último aporte, menor quando sobra resto
 * @param deadline                  o prazo considerado, se houver
 * @param withinDeadline            a conclusão cabe no prazo (vazio sem prazo ou sem conclusão)
 * @param monthsFromDeadline        conclusão − prazo, em meses (negativo = antes do prazo)
 * @param requiredMonthlyForDeadline o menor aporte mensal que cumpre o prazo, em centavos para cima
 * @param incomeShare               aporte ÷ renda média mensal, em % inteiro {@code HALF_UP}; vazio sem renda
 * @param tooFar                    a conclusão passaria de {@value com.smarthealthfinance.goals.domain.service.SimulationEngine#MAX_MONTHS} meses
 */
public record SimulationScenario(Money monthlyAmount, YearMonth completion, int contributions,
                                 YearMonth firstContribution, Money lastContributionAmount, YearMonth deadline,
                                 Boolean withinDeadline, Integer monthsFromDeadline, Money requiredMonthlyForDeadline,
                                 Integer incomeShare, boolean tooFar) {

    public SimulationScenario {
        Objects.requireNonNull(monthlyAmount, "monthlyAmount");
        Objects.requireNonNull(firstContribution, "firstContribution");
    }

    public Optional<YearMonth> completionIfAny() {
        return Optional.ofNullable(completion);
    }
}
