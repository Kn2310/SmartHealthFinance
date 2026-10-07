package com.smarthealthfinance.overview.domain.model;

import com.smarthealthfinance.shared.domain.Money;

import java.util.Currency;
import java.util.Objects;

/**
 * Fluxo de caixa do período (ADR-0006), sempre sobre transações POSTED de contas ativas incluídas no total.
 * <ul>
 *   <li>{@code income}: INCOME;</li>
 *   <li>{@code expense}: EXPENSE bruta;</li>
 *   <li>{@code refunds}: REFUND, que devolve despesa e por isso nunca entra em {@code income};</li>
 *   <li>transferências e ajustes ficam de fora: não são receita nem despesa.</li>
 * </ul>
 */
public record CashFlow(Money income, Money expense, Money refunds) {

    public CashFlow {
        Objects.requireNonNull(income, "income");
        Objects.requireNonNull(expense, "expense");
        Objects.requireNonNull(refunds, "refunds");
    }

    public static CashFlow empty(Currency currency) {
        Money zero = Money.zero(currency);
        return new CashFlow(zero, zero, zero);
    }

    /** Resultado do período: receitas + reembolsos − despesas. */
    public Money net() {
        return income.plus(refunds).minus(expense);
    }
}
