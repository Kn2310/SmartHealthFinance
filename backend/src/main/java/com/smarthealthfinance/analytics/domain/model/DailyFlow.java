package com.smarthealthfinance.analytics.domain.model;

import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.enums.CashFlowRole;

import java.time.LocalDate;
import java.util.Objects;

/** Total de um papel no fluxo (INCOME, EXPENSE ou REFUND) num dia, só de contas ativas incluídas no total. */
public record DailyFlow(LocalDate day, CashFlowRole role, Money amount, int transactionCount) {

    public DailyFlow {
        Objects.requireNonNull(day, "day");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(amount, "amount");
    }
}
