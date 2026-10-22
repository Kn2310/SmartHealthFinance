package com.smarthealthfinance.cards.application.dto;

import com.smarthealthfinance.cards.domain.enums.InvoiceStatus;
import com.smarthealthfinance.cards.domain.model.CardInvoice;
import com.smarthealthfinance.cards.domain.model.CreditCard;
import com.smarthealthfinance.cards.domain.service.InvoiceCalendar;
import com.smarthealthfinance.cards.domain.service.InvoiceLedger;
import com.smarthealthfinance.cards.domain.valueobject.InvoiceDates;
import com.smarthealthfinance.cards.domain.valueobject.InvoiceStatement;
import com.smarthealthfinance.shared.domain.Money;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/**
 * Tudo o que as leituras de um cartão precisam, calculado uma vez: calendário, faturas persistidas e razão.
 *
 * @param current datas da fatura cujo período contém hoje
 */
public record CardLedgerSnapshot(CreditCard card, InvoiceCalendar calendar, List<CardInvoice> rows,
                                 InvoiceLedger ledger, InvoiceDates current, LocalDate today) {

    public Optional<CardInvoice> row(YearMonth closingMonth) {
        return rows.stream().filter(row -> row.dates().closingMonth().equals(closingMonth)).findFirst();
    }

    /**
     * Situação da fatura: a do razão, ou zero para uma fatura futura ainda sem lançamentos. Fatura passada sem
     * lançamentos não existe (vazio).
     */
    public Optional<InvoiceStatement> statement(InvoiceDates dates) {
        Optional<InvoiceStatement> known = ledger.statements().stream()
                .filter(statement -> statement.dates().closingMonth().equals(dates.closingMonth()))
                .findFirst();
        if (known.isPresent() || dates.hasStartedOn(today)) {
            return known;
        }
        Money zero = Money.zero(card.limit().value().currency());
        return Optional.of(new InvoiceStatement(dates, InvoiceStatus.FUTURE, zero, zero, zero, zero, zero));
    }

    /** O carregador sempre põe a fatura atual no razão, mesmo sem lançamentos. */
    public InvoiceStatement currentStatement() {
        return statement(current).orElseThrow();
    }
}
