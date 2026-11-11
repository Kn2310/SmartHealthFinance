package com.smarthealthfinance.copilot.infrastructure.tools;

import com.smarthealthfinance.analytics.application.dto.AnalysisViews;
import com.smarthealthfinance.analytics.application.service.AnalysisContextLoader;
import com.smarthealthfinance.analytics.domain.enums.DataState;
import com.smarthealthfinance.analytics.domain.enums.ProjectionState;
import com.smarthealthfinance.analytics.domain.model.Variation;
import com.smarthealthfinance.copilot.application.port.FinancialTool;
import com.smarthealthfinance.copilot.domain.enums.DataFreshness;
import com.smarthealthfinance.shared.domain.Money;

import java.time.YearMonth;
import java.util.Optional;

/**
 * Peças comuns das tools de Analytics ({@code CASH_FLOW}, {@code CATEGORY}, {@code MERCHANT}): consulta do mês
 * pedido, estado dos dados e freshness da projeção. Percentuais de {@link Variation} e de participação já vêm em
 * pontos percentuais (1 casa) do caso de uso e seguem sem conversão.
 */
final class AnalyticsSupport {

    static final String SOURCE = "analytics-v1";

    private AnalyticsSupport() {
    }

    static AnalysisContextLoader.Query query(FinancialTool.Request request) {
        String month = request.decision().monthValue().map(YearMonth::toString).orElse(null);
        return new AnalysisContextLoader.Query(month, null, null);
    }

    /** Projeção não construída ou sem nenhuma movimentação: não há o que enviar ao modelo. */
    static Optional<FinancialTool.Status> unavailable(AnalysisViews.Meta meta) {
        if (meta.freshness() != null && meta.freshness().state() == ProjectionState.NOT_BUILT) {
            return Optional.of(FinancialTool.Status.NOT_BUILT);
        }
        if (meta.state() == DataState.NO_DATA) {
            return Optional.of(FinancialTool.Status.NO_DATA);
        }
        return Optional.empty();
    }

    static DataFreshness freshness(AnalysisViews.Meta meta) {
        if (meta.freshness() == null) {
            return DataFreshness.UP_TO_DATE;
        }
        return switch (meta.freshness().state()) {
            case UP_TO_DATE -> DataFreshness.UP_TO_DATE;
            case UPDATING -> DataFreshness.UPDATING;
            case NOT_BUILT -> DataFreshness.NOT_BUILT;
        };
    }

    static String period(AnalysisViews.Meta meta) {
        return ToolValues.range(meta.period().from(), meta.period().to());
    }

    /** Diferença em dinheiro e percentual contra o mês anterior, quando há base. */
    static void variation(ToolValues.Builder values, String subject, Variation variation, String period) {
        if (variation == null) {
            return;
        }
        values.money("Variação de " + subject + " contra o mês anterior", variation.difference(), period)
                .percent("Variação percentual de " + subject + " contra o mês anterior", variation.percent(), period);
    }

    static boolean anyMovement(Money... amounts) {
        for (Money amount : amounts) {
            if (amount != null && amount.amount().signum() != 0) {
                return true;
            }
        }
        return false;
    }
}
