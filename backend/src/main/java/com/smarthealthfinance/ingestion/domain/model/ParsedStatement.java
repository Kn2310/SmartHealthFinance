package com.smarthealthfinance.ingestion.domain.model;

import com.smarthealthfinance.ingestion.domain.enums.ImportFormat;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Resultado do parse de um extrato: linhas válidas e linhas recusadas, em ordem de arquivo (ADR-0009 §7).
 * Erros do arquivo inteiro não chegam aqui: viram {@code StatementRejectedException}.
 */
public record ParsedStatement(ImportFormat format, List<StatementLine> lines, List<LineIssue> issues) {

    /** Limite para que o processamento caiba numa transação de banco (ADR-0009 §3). */
    public static final int MAX_LINES = 5_000;

    public ParsedStatement {
        Objects.requireNonNull(format, "format");
        lines = List.copyOf(lines);
        issues = List.copyOf(issues);
    }

    public int totalLines() {
        return lines.size() + issues.size();
    }

    public Optional<LocalDate> firstDate() {
        return lines.stream().map(StatementLine::occurredOn).min(Comparator.naturalOrder());
    }

    public Optional<LocalDate> lastDate() {
        return lines.stream().map(StatementLine::occurredOn).max(Comparator.naturalOrder());
    }
}
