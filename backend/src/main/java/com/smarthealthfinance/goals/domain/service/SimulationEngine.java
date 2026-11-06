package com.smarthealthfinance.goals.domain.service;

import com.smarthealthfinance.goals.domain.valueobject.SimulationScenario;
import com.smarthealthfinance.shared.domain.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Motor de simulação {@code goals-v1} (ADR-0034 §4, §5): aportes mensais iguais, sem rendimento, a partir do mês
 * seguinte ao corrente. Puro, determinístico e em {@code BigDecimal}. A mesma conta dá o central da previsão
 * (Estimado) e os cenários (Simulado); quem chama rotula o resultado.
 * <p>
 * Conclusão = início + ⌈faltante ÷ aporte⌉ − 1. Exemplo (Lisboa, faltam R$ 7.700, início out/2026): R$ 700 → 11
 * aportes, ago/2027; R$ 780 → 10, jul/2027; R$ 900 → 9, jun/2027.
 */
public final class SimulationEngine {

    /** Teto da conta: além de 50 anos a conclusão não é informada. */
    public static final int MAX_MONTHS = 600;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private SimulationEngine() {
    }

    /** O mês do primeiro aporte: sempre o seguinte ao corrente (ADR-0034 §4). */
    public static YearMonth start(YearMonth currentMonth) {
        return currentMonth.plusMonths(1);
    }

    /**
     * @param remaining     quanto falta (nunca negativo)
     * @param monthly       o aporte mensal (zero = sem conclusão)
     * @param deadline      o prazo, opcional
     * @param averageIncome a renda média mensal, opcional (sem ela, sem peso na renda)
     */
    public static SimulationScenario simulate(Money remaining, Money monthly, YearMonth currentMonth,
                                              YearMonth deadline, Money averageIncome) {
        Objects.requireNonNull(remaining, "remaining");
        Objects.requireNonNull(monthly, "monthly");
        Objects.requireNonNull(currentMonth, "currentMonth");
        if (remaining.amount().signum() < 0) {
            throw new IllegalArgumentException("o faltante não pode ser negativo");
        }
        if (monthly.amount().signum() < 0) {
            throw new IllegalArgumentException("o aporte não pode ser negativo");
        }
        YearMonth start = start(currentMonth);
        Money required = deadline == null ? null : requiredMonthly(remaining, start, deadline);
        Integer share = incomeShare(monthly, averageIncome);
        Money zero = Money.zero(remaining.currency());

        if (remaining.amount().signum() == 0) {
            return new SimulationScenario(monthly, null, 0, start, zero, deadline, deadline == null ? null : true,
                    null, required, share, false);
        }
        if (monthly.amount().signum() == 0) {
            return new SimulationScenario(monthly, null, 0, start, zero, deadline, null, null, required, share,
                    false);
        }

        BigDecimal[] division = remaining.amount().divideAndRemainder(monthly.amount());
        long count = division[0].longValueExact() + (division[1].signum() > 0 ? 1 : 0);
        if (count > MAX_MONTHS) {
            return new SimulationScenario(monthly, null, 0, start, zero, deadline, deadline == null ? null : false,
                    null, required, share, true);
        }
        int contributions = (int) count;
        YearMonth completion = start.plusMonths(contributions - 1L);
        Money last = remaining.minus(new Money(monthly.amount().multiply(BigDecimal.valueOf(contributions - 1L)),
                remaining.currency()));
        Boolean within = deadline == null ? null : !completion.isAfter(deadline);
        Integer fromDeadline = deadline == null ? null : (int) ChronoUnit.MONTHS.between(deadline, completion);
        return new SimulationScenario(monthly, completion, contributions, start, last, deadline, within,
                fromDeadline, required, share, false);
    }

    /**
     * O menor aporte mensal, em centavos para cima, que conclui até o prazo; vazio quando o prazo é anterior ao
     * primeiro aporte (já não dá para cumprir com aportes).
     */
    public static Money requiredMonthly(Money remaining, YearMonth start, YearMonth deadline) {
        Objects.requireNonNull(deadline, "deadline");
        long months = ChronoUnit.MONTHS.between(start, deadline) + 1;
        if (months <= 0) {
            return null;
        }
        BigDecimal amount = remaining.amount().divide(BigDecimal.valueOf(months), 2, RoundingMode.CEILING);
        return new Money(amount, remaining.currency());
    }

    /** Peso na renda (ADR-0034 §5): aporte ÷ renda média, % inteiro {@code HALF_UP}; ex.: 300 ÷ 6.600 → 5. */
    public static Integer incomeShare(Money monthly, Money averageIncome) {
        if (averageIncome == null || averageIncome.amount().signum() <= 0) {
            return null;
        }
        return monthly.amount().multiply(HUNDRED).divide(averageIncome.amount(), 0, RoundingMode.HALF_UP)
                .intValueExact();
    }
}
