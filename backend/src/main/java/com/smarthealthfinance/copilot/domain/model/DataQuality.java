package com.smarthealthfinance.copilot.domain.model;

import com.smarthealthfinance.copilot.domain.enums.DataFreshness;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Qualidade dos dados do contexto (ADR-0039 §5): meses com dados, período parcial, lacunas (códigos fixos, ex.:
 * {@code MISSING_INVOICE}), freshness dos read models e fontes ({@code OFX}, {@code CSV}, {@code MANUAL}). A presença
 * de estimados e simulados vem dos próprios valores, em {@link CopilotContext}. {@code monthsWithData} nulo: não
 * se aplica à intenção (ex.: saldo de hoje).
 */
public record DataQuality(Integer monthsWithData, boolean partialPeriod, List<String> gaps,
                          DataFreshness freshness, Set<String> sources) {

    public static final DataQuality EMPTY = new DataQuality(null, false, List.of(), DataFreshness.UP_TO_DATE,
            Set.of());

    public DataQuality {
        Objects.requireNonNull(freshness, "freshness");
        if (monthsWithData != null && monthsWithData < 0) {
            throw new IllegalArgumentException("monthsWithData must not be negative");
        }
        gaps = List.copyOf(gaps);
        sources = Set.copyOf(sources);
    }

    /** Junta a qualidade de duas tools: o menor histórico, a pior freshness, a união de lacunas e fontes. */
    public DataQuality merge(DataQuality other) {
        if (this == EMPTY) {
            return other;
        }
        if (other == EMPTY) {
            return this;
        }
        TreeSet<String> allGaps = new TreeSet<>(gaps);
        allGaps.addAll(other.gaps);
        TreeSet<String> allSources = new TreeSet<>(sources);
        allSources.addAll(other.sources);
        return new DataQuality(minimum(monthsWithData, other.monthsWithData), partialPeriod || other.partialPeriod,
                List.copyOf(allGaps), freshness.worst(other.freshness), allSources);
    }

    private static Integer minimum(Integer a, Integer b) {
        if (a == null) {
            return b;
        }
        return b == null ? a : Math.min(a, b);
    }
}
