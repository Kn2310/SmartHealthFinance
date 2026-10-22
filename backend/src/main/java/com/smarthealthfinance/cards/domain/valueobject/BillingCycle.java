package com.smarthealthfinance.cards.domain.valueobject;

import com.smarthealthfinance.shared.domain.InvalidValueException;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * Dias de fechamento e de vencimento do cartão e a regra que os transforma em datas (ADR-0025 §5, {@code invoice-v1}).
 * <ul>
 *   <li>dia que não existe no mês vira o último dia do mês (31 → 30 set; 29/30/31 → 28 ou 29 fev);</li>
 *   <li>vence no mesmo mês do fechamento se o dia de vencimento for maior que o de fechamento; senão, no seguinte.</li>
 * </ul>
 * No ciclo de mesmo mês o fechamento vai até o dia 27, para o vencimento ficar sempre depois do fechamento (e num mês
 * só seu) também em fevereiro.
 */
public record BillingCycle(
        int closingDay,
        int dueDay
) {
    public static final int MAX_SAME_MONTH_CLOSING_DAY = 27;

    public BillingCycle {
        if (closingDay < 1 || closingDay > 31) {
            throw new InvalidValueException("closingDay", "OUT_OF_RANGE");
        }

        if (dueDay < 1 || dueDay > 31) {
            throw new InvalidValueException("dueDay", "OUT_OF_RANGE");
        }

        if (dueDay == closingDay) {
            throw new InvalidValueException("dueDay", "SAME_AS_CLOSING");
        }

        if (dueDay > closingDay && closingDay > MAX_SAME_MONTH_CLOSING_DAY) {
            throw new InvalidValueException("dueDay", "CONFLICTS_WITH_CLOSING");
        }
    }

    /** 0 = vence no mês do fechamento; 1 = no mês seguinte. */
    public int dueMonthOffset() {
        return dueDay > closingDay ? 0 : 1;
    }

    public LocalDate closingDate(YearMonth closingMonth) {
        return clamp(closingMonth, closingDay);
    }

    public YearMonth dueMonth(YearMonth closingMonth) {
        return closingMonth.plusMonths(dueMonthOffset());
    }

    public LocalDate dueDate(YearMonth closingMonth) {
        return clamp(dueMonth(closingMonth), dueDay);
    }

    public YearMonth closingMonthOfDueMonth(YearMonth dueMonth) {
        return dueMonth.minusMonths(dueMonthOffset());
    }

    private static LocalDate clamp(YearMonth month, int day) {
        return month.atDay(Math.min(day, month.lengthOfMonth()));
    }
}
