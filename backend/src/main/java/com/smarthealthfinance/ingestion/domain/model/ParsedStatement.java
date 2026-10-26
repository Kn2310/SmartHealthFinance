package com.smarthealthfinance.ingestion.domain.model;

import com.smarthealthfinance.ingestion.domain.enums.ImportFormat;
import com.smarthealthfinance.shared.domain.Money;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Resultado do parse de um extrato: linhas válidas e linhas recusadas, em ordem de arquivo (ADR-0009 §7).
 * Erros do arquivo inteiro não chegam aqui: viram {@code StatementRejectedException}.
 *
 * @param closing dados da fatura que o arquivo traz explicitamente (OFX {@code CCCLOSING}), ou {@code null}
 */
public record ParsedStatement(ImportFormat format, List<StatementLine> lines, List<LineIssue> issues,
                              StatementClosing closing) {

    /** Limite para que o processamento caiba numa transação de banco (ADR-0009 §3). */
    public static final int MAX_LINES = 5_000;

    /**
     * Vencimento e pagamento mínimo informados no arquivo (ADR-0009 §21); cada um pode faltar, mas não os dois.
     */
    public record StatementClosing(LocalDate dueDate, Money minimumPayment) {

        public StatementClosing {
            if (dueDate == null && minimumPayment == null) {
                throw new IllegalArgumentException("Sem dados da fatura");
            }
        }
    }

    public ParsedStatement {
        Objects.requireNonNull(format, "format");
        lines = List.copyOf(lines);
        issues = List.copyOf(issues);
    }

    public ParsedStatement(ImportFormat format, List<StatementLine> lines, List<LineIssue> issues) {
        this(format, lines, issues, null);
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
