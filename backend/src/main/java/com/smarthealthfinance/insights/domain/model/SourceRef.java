package com.smarthealthfinance.insights.domain.model;

import com.smarthealthfinance.insights.domain.enums.InsightSource;
import java.time.LocalDate;
import java.util.Objects;

/** Fonte de um número: a porta e o período lido (ADR-0021 §7). */
public record SourceRef(InsightSource source, LocalDate from, LocalDate to) {

    public SourceRef {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
    }
}
