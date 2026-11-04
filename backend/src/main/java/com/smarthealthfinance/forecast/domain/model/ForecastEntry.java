package com.smarthealthfinance.forecast.domain.model;

import com.smarthealthfinance.analytics.domain.model.FlowEntry;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.transactions.domain.enums.CashFlowRole;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Uma transação lançada (POSTED) do fluxo de caixa, com o que o Forecast precisa além do {@link FlowEntry}: o id, o
 * canal (conta ou cartão) e se é compra parcelada. Uma linha por transação.
 *
 * @param categoryCode {@code null} = "Sem categoria"
 * @param merchantKey  {@code null} = comerciante não identificado
 * @param installment  compra parcelada ou parcela de cartão: nunca vira recorrência (ADR-0030 §2)
 */
public record ForecastEntry(UUID transactionId, LocalDate day, CashFlowRole role, String categoryCode,
                            String merchantKey, String merchantName, Money amount, Channel channel,
                            boolean installment) {

    public ForecastEntry {
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(day, "day");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(channel, "channel");
        if (role != CashFlowRole.INCOME && role != CashFlowRole.EXPENSE && role != CashFlowRole.REFUND) {
            throw new IllegalArgumentException("ForecastEntry só para INCOME, EXPENSE e REFUND");
        }
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("amount deve ser positivo");
        }
    }

    /** A mesma linha no formato do Analytics, para reaproveitar {@code IncomeSources.sourceKey}. */
    public FlowEntry toFlowEntry() {
        return new FlowEntry(day, role, categoryCode, merchantKey, merchantName, amount, 1);
    }
}
