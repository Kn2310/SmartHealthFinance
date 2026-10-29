package com.smarthealthfinance.analytics.domain.model;

import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.enums.CashFlowRole;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Uma transação do fluxo de caixa (INCOME, EXPENSE ou REFUND, POSTED, de conta ativa incluída no total ou de cartão
 * ativo): a mesma regra de {@link FlowEntry}, mas sem agrupar, para o Forecast (ADR-0031 §11, adendo ao ADR-0014).
 * Somar por dia, papel, categoria e comerciante dá exatamente as linhas de {@code entries}.
 *
 * @param categoryCode {@code null} = "Sem categoria"
 * @param merchantKey  {@code null} = comerciante não identificado
 * @param accountId    conta de origem; nulo quando a transação é de cartão
 * @param cardId       cartão; nulo quando a transação é de conta
 * @param installment  compra de cartão com plano de mais de 1 parcela
 */
public record FlowTransaction(UUID transactionId, LocalDate day, CashFlowRole role, String categoryCode,
                              String merchantKey, String merchantName, Money amount, UUID accountId, UUID cardId,
                              boolean installment) {

    public FlowTransaction {
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(day, "day");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(amount, "amount");
        if (role == CashFlowRole.TRANSFER || role == CashFlowRole.NONE) {
            throw new IllegalArgumentException("FlowTransaction só para INCOME, EXPENSE e REFUND");
        }
        if ((accountId == null) == (cardId == null)) {
            throw new IllegalArgumentException("Exatamente um entre conta e cartão");
        }
    }
}
