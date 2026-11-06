package com.smarthealthfinance.goals.domain.valueobject;

import com.smarthealthfinance.shared.domain.InvalidValueException;
import com.smarthealthfinance.shared.domain.Money;

import java.time.YearMonth;
import java.util.Currency;
import java.util.Objects;
import java.util.Optional;

/**
 * O plano do objetivo (ADR-0034 §1): alvo maior que zero, aporte mensal maior ou igual a zero (zero = sem plano) e
 * prazo opcional, sempre na moeda do Workspace (BRL) e com no máximo 2 casas.
 */
public record GoalPlan(Money targetAmount, Money plannedMonthly, YearMonth deadline) {

    public static final Currency CURRENCY = Currency.getInstance("BRL");

    public GoalPlan {
        Objects.requireNonNull(targetAmount, "targetAmount");
        Objects.requireNonNull(plannedMonthly, "plannedMonthly");
        requireCurrency("targetAmount", targetAmount);
        requireCurrency("plannedMonthly", plannedMonthly);
        if (!targetAmount.isPositive()) {
            throw new InvalidValueException("targetAmount", "MUST_BE_POSITIVE");
        }
        if (plannedMonthly.amount().signum() < 0) {
            throw new InvalidValueException("plannedMonthly", "NEGATIVE");
        }
    }

    /** O prazo precisa ser posterior ao mês corrente quando é definido ou alterado (ADR-0034 §1). */
    public void requireDeadlineAfter(YearMonth currentMonth) {
        if (deadline != null && !deadline.isAfter(currentMonth)) {
            throw new InvalidValueException("deadline", "PAST");
        }
    }

    public Optional<YearMonth> deadlineIfAny() {
        return Optional.ofNullable(deadline);
    }

    private static void requireCurrency(String field, Money money) {
        if (!money.currency().equals(CURRENCY)) {
            throw new InvalidValueException(field + ".currency", "UNSUPPORTED");
        }
        if (money.decimalPlaces() > CURRENCY.getDefaultFractionDigits()) {
            throw new InvalidValueException(field, "TOO_MANY_DECIMALS");
        }
    }
}
