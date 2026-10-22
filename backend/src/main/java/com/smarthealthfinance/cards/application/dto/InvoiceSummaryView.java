package com.smarthealthfinance.cards.application.dto;

import com.smarthealthfinance.cards.domain.enums.InvoiceDatesSource;
import com.smarthealthfinance.cards.domain.enums.InvoiceStatus;
import com.smarthealthfinance.cards.domain.model.CardInvoice;
import com.smarthealthfinance.cards.domain.valueobject.InvoiceDates;
import com.smarthealthfinance.cards.domain.valueobject.InvoiceStatement;
import com.smarthealthfinance.shared.domain.Money;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * Fatura resumida (ADR-0026 §4). {@code partial} = aberta: o valor pode mudar até o fechamento.
 *
 * @param minimumPayment nulo até a importação informar (Sessão 6.2)
 */
public record InvoiceSummaryView(
        YearMonth dueMonth, LocalDate periodStart, LocalDate closingDate, LocalDate dueDate,
        LocalDate effectiveDueDate, InvoiceDatesSource datesSource, InvoiceStatus status, Money charges,
        Money credits, Money amount, Money paid, Money remaining, Money minimumPayment, boolean partial
) {
    /** @param row linha persistida, nula para a fatura atual ou futura ainda sem lançamentos */
    public static InvoiceSummaryView from(InvoiceStatement statement, CardInvoice row) {
        InvoiceDates dates = statement.dates();
        return new InvoiceSummaryView(dates.dueMonth(), dates.periodStart(), dates.closingDate(), dates.dueDate(),
                dates.effectiveDueDate(), dates.source(), statement.status(), statement.charges(),
                statement.credits(), statement.amount(), statement.paid(), statement.remaining(),
                row == null ? null : row.minimumPayment(), statement.status() == InvoiceStatus.OPEN);
    }
}
