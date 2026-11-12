package com.smarthealthfinance.copilot.infrastructure.tools;

import com.smarthealthfinance.analytics.application.dto.AnalysisViews;
import com.smarthealthfinance.analytics.application.service.AnalysisContextLoader;
import com.smarthealthfinance.analytics.application.usecase.AnalysisQueries;
import com.smarthealthfinance.copilot.application.port.DataScopeReader;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Recorte de dados (ADR-0040 §8) pela evolução mensal de {@link AnalysisQueries#evolution}, a mesma que a tool de fluxo
 * de caixa usa: do primeiro ao último dos últimos 12 meses com movimentação. Autoriza o membro pelo caso de uso.
 */
@Component
public class AnalyticsDataScopeReader implements DataScopeReader {

    private final AnalysisQueries analysis;

    public AnalyticsDataScopeReader(AnalysisQueries analysis) {
        this.analysis = analysis;
    }

    @Override
    public Optional<Scope> read(UUID workspaceId) {
        AnalysisViews.Evolution evolution = analysis.evolution(workspaceId, AnalysisContextLoader.Query.defaults(),
                AnalysisQueries.MAX_EVOLUTION_MONTHS);
        List<YearMonth> withData = evolution.months().stream()
                .filter(bar -> AnalyticsSupport.anyMovement(bar.income(), bar.expense(), bar.refunds()))
                .map(AnalysisViews.MonthBar::month)
                .sorted(Comparator.naturalOrder())
                .toList();
        return withData.isEmpty() ? Optional.empty() : Optional.of(new Scope(withData.getFirst(), withData.getLast()));
    }
}
