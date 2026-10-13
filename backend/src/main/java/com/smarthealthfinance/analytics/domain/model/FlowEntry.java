package com.smarthealthfinance.analytics.domain.model;

import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.enums.CashFlowRole;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Uma linha da projeção já filtrada para o fluxo de caixa (INCOME, EXPENSE ou REFUND de contas ativas incluídas no
 * total): o insumo de todos os cálculos da Análise (ADR-0015 §1).
 *
 * @param categoryCode {@code null} = "Sem categoria"
 * @param merchantKey  {@code null} = comerciante não identificado
 */
public record FlowEntry(LocalDate day, CashFlowRole role, String categoryCode, String merchantKey,
                        String merchantName, Money amount, int transactionCount) {

    public FlowEntry {
        Objects.requireNonNull(day, "day");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(amount, "amount");
        if (role == CashFlowRole.TRANSFER || role == CashFlowRole.NONE) {
            throw new IllegalArgumentException("FlowEntry só para INCOME, EXPENSE e REFUND");
        }
    }
}
