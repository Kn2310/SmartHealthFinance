package com.smarthealthfinance.goals.domain.valueobject;

import com.smarthealthfinance.goals.domain.enums.GoalConfidence;
import com.smarthealthfinance.goals.domain.enums.GoalForecastReason;
import com.smarthealthfinance.goals.domain.enums.GoalForecastStatus;
import com.smarthealthfinance.shared.domain.Money;

import java.time.YearMonth;
import java.util.Objects;

/**
 * Previsão de conclusão, sempre {@code ESTIMATED} (ADR-0034 §4). Só {@code status} e {@code reason} existem em
 * {@code NO_FORECAST} e {@code REACHED}; nos demais há conclusão, faixa e confiança.
 *
 * @param completion          o central, pelo plano
 * @param contributionsLeft   aportes que faltam pelo plano
 * @param monthsLate          meses depois do prazo ({@code LATE})
 * @param monthsEarly         meses antes do prazo ({@code ON_TRACK}, zero no próprio mês)
 * @param pessimistic         a conclusão pelo ritmo real, quando ele é menor que o plano
 * @param openEnded           o ritmo real é zero ou negativo: não há conclusão pessimista
 * @param realPace            mediana do aporte líquido dos meses fechados desde o início (até 3); vazio sem mês fechado
 */
public record GoalForecast(GoalForecastStatus status, GoalForecastReason reason, YearMonth completion,
                           Integer contributionsLeft, Integer monthsLate, Integer monthsEarly, YearMonth pessimistic,
                           boolean openEnded, GoalConfidence confidence, Money realPace) {

    public GoalForecast {
        Objects.requireNonNull(status, "status");
        if ((status == GoalForecastStatus.NO_FORECAST) != (reason != null)) {
            throw new IllegalArgumentException("reason só em NO_FORECAST");
        }
    }

    public static GoalForecast none(GoalForecastReason reason) {
        return new GoalForecast(GoalForecastStatus.NO_FORECAST, reason, null, null, null, null, null, false, null,
                null);
    }

    public static GoalForecast reached() {
        return new GoalForecast(GoalForecastStatus.REACHED, null, null, null, null, null, null, false, null, null);
    }

    public boolean hasCompletion() {
        return completion != null;
    }
}
