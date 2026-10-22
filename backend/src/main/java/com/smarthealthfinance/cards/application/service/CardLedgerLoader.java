package com.smarthealthfinance.cards.application.service;

import com.smarthealthfinance.cards.application.dto.CardLedgerSnapshot;
import com.smarthealthfinance.cards.application.port.CardLedgerReader;
import com.smarthealthfinance.cards.application.port.CardLedgerReader.CardTotals;
import com.smarthealthfinance.cards.application.port.CardLedgerReader.InvoiceTotals;
import com.smarthealthfinance.cards.domain.model.CardInvoice;
import com.smarthealthfinance.cards.domain.model.CreditCard;
import com.smarthealthfinance.cards.domain.repository.CardInvoiceRepository;
import com.smarthealthfinance.cards.domain.service.InvoiceCalendar;
import com.smarthealthfinance.cards.domain.service.InvoiceLedger;
import com.smarthealthfinance.cards.domain.valueobject.CardId;
import com.smarthealthfinance.cards.domain.valueobject.InvoiceBalance;
import com.smarthealthfinance.cards.domain.valueobject.InvoiceDates;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.Money;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Monta o razão de um ou de vários cartões (ADR-0026 §7): as faturas persistidas com as somas do SQL, mais a fatura
 * atual mesmo sem lançamentos. O cálculo é do domínio; aqui só se juntam as entradas.
 */
@Service
public class CardLedgerLoader {

    private final CardInvoiceRepository invoices;
    private final CardLedgerReader reader;

    public CardLedgerLoader(CardInvoiceRepository invoices, CardLedgerReader reader) {
        this.invoices = invoices;
        this.reader = reader;
    }

    public CardLedgerSnapshot load(CreditCard card, LocalDate today) {
        return snapshot(card, invoices.findByCard(card.workspaceId(), card.id()),
                reader.totals(card.workspaceId(), card.id()), today);
    }

    /** Uma consulta de faturas e uma de somas para todos os cartões (sem N+1). */
    public Map<CardId, CardLedgerSnapshot> loadAll(WorkspaceId workspaceId, List<CreditCard> cards, LocalDate today) {
        Map<CardId, List<CardInvoice>> rowsByCard = new LinkedHashMap<>();
        for (CardInvoice row : invoices.findByWorkspace(workspaceId)) {
            rowsByCard.computeIfAbsent(row.cardId(), ignored -> new ArrayList<>()).add(row);
        }
        Map<CardId, CardTotals> totals = reader.totalsByCard(workspaceId);

        Map<CardId, CardLedgerSnapshot> snapshots = new LinkedHashMap<>();
        for (CreditCard card : cards) {
            snapshots.put(card.id(), snapshot(card, rowsByCard.getOrDefault(card.id(), List.of()),
                    totals.getOrDefault(card.id(), new CardTotals(Map.of(), zero(card))), today));
        }
        return snapshots;
    }

    private static CardLedgerSnapshot snapshot(CreditCard card, List<CardInvoice> rows, CardTotals totals,
                                               LocalDate today) {
        Money zero = zero(card);
        InvoiceCalendar calendar = new InvoiceCalendar(card.cycle(), rows.stream().map(CardInvoice::dates).toList());
        InvoiceDates current = calendar.invoiceOf(today);

        List<InvoiceBalance> balances = new ArrayList<>();
        boolean currentStored = false;
        for (CardInvoice row : rows) {
            InvoiceTotals sums = totals.invoices().getOrDefault(row.id(), new InvoiceTotals(zero, zero));
            balances.add(new InvoiceBalance(row.dates(), sums.charges(), sums.credits()));
            currentStored |= row.dates().closingMonth().equals(current.closingMonth());
        }
        if (!currentStored) {
            balances.add(new InvoiceBalance(current, zero, zero));
        }

        InvoiceLedger ledger = InvoiceLedger.compute(balances, totals.payments(), today);
        return new CardLedgerSnapshot(card, calendar, List.copyOf(rows), ledger, current, today);
    }

    private static Money zero(CreditCard card) {
        return Money.zero(card.limit().value().currency());
    }
}
