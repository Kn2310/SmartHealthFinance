package com.smarthealthfinance.transactions.presentation.dto.response;

import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.shared.presentation.money.MoneyDto;
import com.smarthealthfinance.transactions.application.port.TransactionSummaryReader.TransactionSummary;

import java.math.BigDecimal;

/**
 * Métricas do período filtrado (ADR-0016). {@code net = income + refunds − expense}; transferências só informativas.
 *
 * @param postedCount  lançadas que passam nos filtros
 * @param pendingCount pendentes que passam nos filtros (fora dos valores)
 */
public record TransactionSummaryResponse(MoneyDto income, MoneyDto expense, MoneyDto refunds, MoneyDto net,
                                         TransactionSummaryTransfers transfers, int postedCount, int pendingCount) {

    public record TransactionSummaryTransfers(int count, MoneyDto volume) {
    }

    public static TransactionSummaryResponse from(TransactionSummary summary) {
        return new TransactionSummaryResponse(money(summary.income()), money(summary.expense()),
                money(summary.refunds()), money(summary.net()),
                new TransactionSummaryTransfers(summary.transferCount(), money(summary.transferVolume())),
                summary.postedCount(), summary.pendingCount());
    }

    private static MoneyDto money(Money money) {
        return MoneyDto.of(new BigDecimal(money.toPlainString()), money.currency().getCurrencyCode());
    }
}
