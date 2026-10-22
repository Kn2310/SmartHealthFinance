package com.smarthealthfinance.cards.domain.valueobject;

import com.smarthealthfinance.cards.domain.enums.InvoiceDatesSource;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;

/**
 * Datas de uma fatura (ADR-0025 §5). O período é {@code [periodStart, closingDate)}: a compra feita no dia do
 * fechamento vai para a próxima fatura. A fatura é chamada pelo mês do vencimento ("Fatura de outubro").
 */
public record InvoiceDates(
        YearMonth closingMonth,
        YearMonth dueMonth,
        LocalDate periodStart,
        LocalDate closingDate,
        LocalDate dueDate,
        InvoiceDatesSource source
) {
    public InvoiceDates {
        Objects.requireNonNull(closingMonth, "closingMonth");
        Objects.requireNonNull(dueMonth, "dueMonth");
        Objects.requireNonNull(periodStart, "periodStart");
        Objects.requireNonNull(closingDate, "closingDate");
        Objects.requireNonNull(dueDate, "dueDate");
        Objects.requireNonNull(source, "source");

        if (!periodStart.isBefore(closingDate) || !closingDate.isBefore(dueDate)) {
            throw new IllegalArgumentException("Datas da fatura fora de ordem");
        }
    }

    public boolean contains(LocalDate date) {
        return !date.isBefore(periodStart) && date.isBefore(closingDate);
    }

    /** Fechada no dia do fechamento (inclusive). */
    public boolean isClosedOn(LocalDate today) {
        return !today.isBefore(closingDate);
    }

    public boolean hasStartedOn(LocalDate today) {
        return !today.isBefore(periodStart);
    }

    /**
     * Vencimento usado só para o atraso: sábado e domingo passam para a segunda. Feriados não são considerados (sem
     * fonte de calendário, ADR-0025 §14); a data exibida continua a nominal.
     */
    public LocalDate effectiveDueDate() {
        return switch (dueDate.getDayOfWeek()) {
            case SATURDAY -> dueDate.plusDays(2);
            case SUNDAY -> dueDate.plusDays(1);
            default -> dueDate;
        };
    }
}
