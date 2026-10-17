package com.smarthealthfinance.insights.domain.model;

import java.util.List;
import java.util.Objects;

/**
 * Evidências e explicação (ADR-0021 §7): números-chave, série mensal, maiores contribuintes, "como comparamos",
 * limitações e fontes.
 */
public record InsightEvidence(List<KeyFigure> figures, List<SeriesPoint> series, List<Contributor> contributors,
                              String comparison, List<Limitation> limitations, List<SourceRef> sources) {

    public InsightEvidence {
        Objects.requireNonNull(comparison, "comparison");
        figures = List.copyOf(figures);
        series = List.copyOf(series);
        contributors = List.copyOf(contributors);
        limitations = List.copyOf(limitations);
        sources = List.copyOf(sources);
    }
}
