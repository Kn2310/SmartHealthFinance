package com.smarthealthfinance.analytics.domain.model;

import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.enums.CashFlowRole;

import java.util.Objects;

/**
 * Total de um papel no fluxo (INCOME, EXPENSE ou REFUND) por categoria, só de contas ativas incluídas no total.
 *
 * @param categoryCode {@code null} = "Sem categoria"
 */
public record CategoryFlow(CashFlowRole role, String categoryCode, Money amount, int transactionCount) {

    public CategoryFlow {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(amount, "amount");
    }
}
