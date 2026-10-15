package com.smarthealthfinance.health.domain.model;

import com.smarthealthfinance.health.domain.enums.EvidenceKind;
import com.smarthealthfinance.health.domain.enums.EvidenceSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Um número usado na nota de uma dimensão (ADR-0017 §6), com o período e a porta que o produziu.
 *
 * @param code  identificador estável (ex.: {@code averageNet})
 * @param label rótulo em português
 */
public record Evidence(String code, String label, EvidenceKind kind, BigDecimal value, LocalDate from,
                       LocalDate to, EvidenceSource source) {

    public Evidence {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(source, "source");
    }
}
