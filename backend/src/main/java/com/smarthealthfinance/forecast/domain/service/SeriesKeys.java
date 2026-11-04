package com.smarthealthfinance.forecast.domain.service;

import com.smarthealthfinance.analytics.domain.service.IncomeSources;
import com.smarthealthfinance.forecast.domain.enums.SeriesDirection;
import com.smarthealthfinance.forecast.domain.model.ForecastEntry;
import com.smarthealthfinance.forecast.domain.model.RecurringSeries;
import com.smarthealthfinance.transactions.domain.enums.CashFlowRole;

/**
 * Sentido e chave de série de uma transação (ADR-0030 §2). A chave é a mesma do {@code IncomeSources.sourceKey}
 * (comerciante; sem comerciante, a categoria); a despesa sem comerciante não tem chave estável.
 */
final class SeriesKeys {

    private SeriesKeys() {
    }

    /** {@code null} para reembolso, que não é receita nem despesa recorrente. */
    static SeriesDirection directionOf(ForecastEntry entry) {
        return switch (entry.role()) {
            case INCOME -> SeriesDirection.INCOME;
            case EXPENSE -> SeriesDirection.EXPENSE;
            default -> null;
        };
    }

    /** {@code null} quando a transação não pode formar uma série. */
    static String keyOf(ForecastEntry entry) {
        if (entry.role() == CashFlowRole.INCOME) {
            return IncomeSources.sourceKey(entry.toFlowEntry());
        }
        if (entry.role() == CashFlowRole.EXPENSE && entry.merchantKey() != null) {
            return IncomeSources.sourceKey(entry.toFlowEntry());
        }
        return null;
    }

    static boolean matches(ForecastEntry entry, RecurringSeries series) {
        return directionOf(entry) == series.direction() && series.key().equals(keyOf(entry));
    }
}
