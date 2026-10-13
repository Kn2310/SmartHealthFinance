package com.smarthealthfinance.analytics.domain.model;

import com.smarthealthfinance.shared.domain.Money;

import java.util.Currency;
import java.util.Objects;

/**
 * Fluxo de caixa de um intervalo (ADR-0006 §9): só POSTED; receitas, despesas e reembolsos só de contas ativas
 * incluídas no total; transferências só informativas (todas as contas), nunca receita nem despesa.
 *
 * @param expense despesa bruta; o reembolso fica em {@code refunds}
 */
public record FlowTotals(Money income, Money expense, Money refunds, int transferCount, Money transferVolume) {

    public FlowTotals {
        Objects.requireNonNull(income, "income");
        Objects.requireNonNull(expense, "expense");
        Objects.requireNonNull(refunds, "refunds");
        Objects.requireNonNull(transferVolume, "transferVolume");
    }

    public static FlowTotals empty(Currency currency) {
        Money zero = Money.zero(currency);
        return new FlowTotals(zero, zero, zero, 0, zero);
    }

    /** Resultado: receitas + reembolsos − despesas (mesma fórmula do Overview). */
    public Money net() {
        return income.plus(refunds).minus(expense);
    }
}
