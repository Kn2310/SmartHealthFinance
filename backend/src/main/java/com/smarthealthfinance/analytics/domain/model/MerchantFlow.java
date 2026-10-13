package com.smarthealthfinance.analytics.domain.model;

import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.enums.CashFlowRole;

import java.util.Objects;

/**
 * Total de um papel no fluxo por comerciante e categoria, só de contas ativas incluídas no total. Um comerciante
 * com transações em duas categorias aparece em duas linhas; quem consome decide como agrupar.
 *
 * @param merchantKey  {@code null} = comerciante não identificado (ADR-0013 §1)
 * @param merchantName nome de exibição; {@code null} junto com a chave
 * @param categoryCode {@code null} = "Sem categoria"
 */
public record MerchantFlow(CashFlowRole role, String merchantKey, String merchantName, String categoryCode,
                           Money amount, int transactionCount) {

    public MerchantFlow {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(amount, "amount");
    }
}
