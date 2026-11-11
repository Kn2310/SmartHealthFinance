package com.smarthealthfinance.copilot.infrastructure.tools;

import com.smarthealthfinance.analytics.application.dto.AnalysisViews;
import com.smarthealthfinance.analytics.application.service.AnalysisContextLoader;
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
 * {@code CASH_FLOW} (ADR-0039 §4) sobre {@link AnalysisQueries#overview} e {@link AnalysisQueries#evolution}:
 * receitas, despesas, estornos e resultado do mês, variação contra o mês anterior, parcela guardada, despesa média e
 * a evolução dos últimos 6 meses. Percentuais já vêm em pontos percentuais do caso de uso.
 */
@Component
public class CashFlowTool implements FinancialTool {

    static final String SOURCE = AnalyticsSupport.SOURCE;
    static final int EVOLUTION_MONTHS = 6;

    private final AnalysisQueries analysis;

    public CashFlowTool(AnalysisQueries analysis) {
        this.analysis = analysis;
    }

    @Override
    public Intent intent() {
        return Intent.CASH_FLOW;
    }

    @Override
    public Result collect(Request request) {
        AnalysisContextLoader.Query query = AnalyticsSupport.query(request);
        AnalysisViews.Overview overview = analysis.overview(request.workspaceId(), query);
        Optional<Status> unavailable = AnalyticsSupport.unavailable(overview.meta());
        if (unavailable.isPresent()) {
            return Result.of(unavailable.get());
        }
        AnalysisViews.Evolution evolution = analysis.evolution(request.workspaceId(), query, EVOLUTION_MONTHS);

        String period = AnalyticsSupport.period(overview.meta());
        ToolValues.Builder values = new ToolValues.Builder(SOURCE);
        metric(values, "receitas", "Receitas do mês", overview.income(), period);
        metric(values, "despesas", "Despesas do mês", overview.expense(), period);
        metric(values, "estornos", "Estornos do mês", overview.refunds(), period);
        metric(values, "resultado", "Resultado do mês (receitas mais estornos menos despesas)", overview.net(),
                period);
        values.percent("Parcela da receita que sobrou no mês (resultado sobre receitas)", overview.savedShare(),
                        period)
                .money("Despesa média mensal dos meses de comparação", overview.averageExpense(), period);

        int monthsWithData = 0;
        for (AnalysisViews.MonthBar bar : evolution.months()) {
            String month = ToolValues.month(bar.month());
            values.money("Receitas no mês da evolução", bar.income(), month)
                    .money("Despesas no mês da evolução", bar.expense(), month)
                    .money("Resultado no mês da evolução", bar.net(), month);
            if (AnalyticsSupport.anyMovement(bar.income(), bar.expense(), bar.refunds())) {
                monthsWithData++;
            }
        }
        DataQuality quality = new DataQuality(monthsWithData, overview.meta().period().partial(), List.of(),
                AnalyticsSupport.freshness(overview.meta()), Set.of());
        return new Result(Status.DATA, values.build(), quality,
                List.of(new SourceRef("AnalysisQueries", SOURCE)), List.of());
    }

    private static void metric(ToolValues.Builder values, String subject, String label,
                               AnalysisViews.Metric metric, String period) {
        if (metric == null) {
            return;
        }
        values.money(label, metric.amount(), period);
        AnalyticsSupport.variation(values, subject, metric.vsPrevious(), period);
    }
}
