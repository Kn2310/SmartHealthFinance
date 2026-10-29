package com.smarthealthfinance.forecast.domain.model;

import com.smarthealthfinance.forecast.domain.enums.Confidence;
import com.smarthealthfinance.shared.domain.Money;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Objects;

/**
 * Previsto × realizado de um mês (ADR-0030 §6, ADR-0031 §6): a projeção congelada na virada do mês, o realizado e o
 * erro. Só backend no v1.
 *
 * @param error nulo enquanto não há realizado
 */
public record MonthOutcome(YearMonth month, String algorithmVersion, Figures projected, Figures projectedLow,
                           Figures projectedHigh, Confidence confidence, Figures realized, Error error) {

    public MonthOutcome {
        Objects.requireNonNull(month, "month");
        Objects.requireNonNull(algorithmVersion, "algorithmVersion");
        Objects.requireNonNull(projected, "projected");
        Objects.requireNonNull(projectedLow, "projectedLow");
        Objects.requireNonNull(projectedHigh, "projectedHigh");
        Objects.requireNonNull(confidence, "confidence");
    }

    /** Despesa, receita e resultado de um mês. */
    public record Figures(Money expense, Money income, Money net) {

        public Figures {
            Objects.requireNonNull(expense, "expense");
            Objects.requireNonNull(income, "income");
            Objects.requireNonNull(net, "net");
        }
    }

    /**
     * @param absolute          {@code realizado − previsto}, com sinal
     * @param expensePercent    erro absoluto da despesa em % do previsto, 2 casas HALF_EVEN; nulo se o previsto é zero
     * @param incomePercent     idem, da receita
     * @param expenseWithinRange o realizado ficou dentro de [mín, máx] da despesa
     * @param incomeWithinRange  idem, da receita
     */
    public record Error(Figures absolute, BigDecimal expensePercent, BigDecimal incomePercent,
                        boolean expenseWithinRange, boolean incomeWithinRange) {}
}
