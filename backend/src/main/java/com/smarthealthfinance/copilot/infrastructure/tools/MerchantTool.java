package com.smarthealthfinance.copilot.infrastructure.tools;

import com.smarthealthfinance.analytics.application.dto.AnalysisViews;
import com.smarthealthfinance.analytics.application.usecase.AnalysisQueries;
import com.smarthealthfinance.copilot.application.port.FinancialTool;
import com.smarthealthfinance.copilot.domain.enums.Intent;
import com.smarthealthfinance.copilot.domain.model.DataQuality;
import com.smarthealthfinance.copilot.domain.model.SourceRef;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * {@code MERCHANT} (ADR-0039 §4) sobre {@link AnalysisQueries#merchants}: até 10 comerciantes do mês por valor, cada
 * um com o nome normalizado (texto, tratado como dado), a categoria do catálogo, o valor, o número de transações e a
 * variação contra o mês anterior, mais o total sem comerciante identificado. Chave e descrição crua nunca entram.
 * Percentual de variação já vem em pontos percentuais do caso de uso.
 */
@Component
public class MerchantTool implements FinancialTool {

    static final String SOURCE = AnalyticsSupport.SOURCE;
    static final int MAX_MERCHANTS = 10;

    private final AnalysisQueries analysis;

    public MerchantTool(AnalysisQueries analysis) {
        this.analysis = analysis;
    }

    @Override
    public Intent intent() {
        return Intent.MERCHANT;
    }

    @Override
    public Result collect(Request request) {
        AnalysisViews.Merchants merchants = analysis.merchants(request.workspaceId(),
                AnalyticsSupport.query(request), null);
        Optional<Status> unavailable = AnalyticsSupport.unavailable(merchants.meta());
        if (unavailable.isPresent()) {
            return Result.of(unavailable.get());
        }
        String period = AnalyticsSupport.period(merchants.meta());
        ToolValues.Builder values = new ToolValues.Builder(SOURCE);
        List<AnalysisViews.MerchantLine> lines = merchants.merchants().stream().limit(MAX_MERCHANTS).toList();
        for (int i = 0; i < lines.size(); i++) {
            AnalysisViews.MerchantLine line = lines.get(i);
            String ordinal = ToolSupport.ordinal(i);
            values.text("Comerciante " + ordinal + " por valor", line.merchantName(), period)
                    .text("Categoria do comerciante " + ordinal,
                            line.categoryCode() == null ? CategoryTool.NO_CATEGORY_NAME
                                    : request.categoryName(line.categoryCode()), period)
                    .money("Despesa no comerciante " + ordinal, line.amount(), period)
                    .count("Transações no comerciante " + ordinal, line.transactionCount(), period);
            AnalyticsSupport.variation(values, "despesa no comerciante " + ordinal, line.vsPrevious(), period);
        }
        values.money("Despesas sem comerciante identificado", merchants.unidentified(), period);

        DataQuality quality = new DataQuality(null, merchants.meta().period().partial(), List.of(),
                AnalyticsSupport.freshness(merchants.meta()), Set.of());
        return new Result(Status.DATA, values.build(), quality,
                List.of(new SourceRef("AnalysisQueries", SOURCE)), List.of());
    }
}
