package com.smarthealthfinance.insights.application.dto;

import com.smarthealthfinance.health.domain.enums.HealthDimension;
import com.smarthealthfinance.insights.application.port.InsightStore;
import com.smarthealthfinance.insights.domain.model.ChangeLine;
import com.smarthealthfinance.insights.domain.model.Insight;
import com.smarthealthfinance.insights.domain.model.InsightsFreshness;
import com.smarthealthfinance.overview.domain.enums.DataSource;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Resultados das consultas de Insights (ADR-0023), antes de virar DTO da API. */
public final class InsightViews {

    private InsightViews() {
    }

    /** {@code NO_DATA}, {@code INSUFFICIENT_HISTORY} ou {@code READY} (ADR-0023 §2). */
    public enum DataState {
        NO_DATA,
        INSUFFICIENT_HISTORY,
        READY
    }

    /** Período avaliado P das respostas. */
    public record Period(YearMonth month, boolean partial) {}

    public record Page(DataState state, InsightsFreshness freshness, Period period, InsightStore.ChipCounts counts,
                       List<Insight> items, int page, int pageSize, long totalItems) {}

    /**
     * @param related     até 3 insights ativos ligados a este
     * @param dimension   a dimensão do Health afetada, com a nota do snapshot de P (nula se não houver)
     * @param dataSources origens dos lançamentos no período das fontes do insight
     */
    public record Detail(InsightsFreshness freshness, Insight insight, List<Insight> related, DimensionScore dimension,
                         Set<DataSource> dataSources) {}

    /** @param score nulo se a dimensão não tem nota no snapshot */
    public record DimensionScore(HealthDimension dimension, Integer score) {}

    public record Change(UUID insightId, Insight insight, ChangeLine line) {}

    public record Highlights(DataState state, InsightsFreshness freshness, Period period, List<Insight> attention,
                             List<Change> changes, long importantCount) {}

    public record Related(InsightsFreshness freshness, List<Insight> items) {}
}
