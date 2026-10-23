package com.smarthealthfinance.cards.application.service;

import com.smarthealthfinance.cards.application.dto.CardLedgerSnapshot;
import com.smarthealthfinance.cards.application.port.CardLedgerReader;
import com.smarthealthfinance.cards.application.port.CardLedgerReader.CardFacts;
import com.smarthealthfinance.cards.application.port.CardLedgerReader.InstallmentFact;
import com.smarthealthfinance.cards.application.port.CardLedgerReader.PaymentFact;
import com.smarthealthfinance.cards.domain.model.CardInvoice;
import com.smarthealthfinance.cards.domain.model.CreditCard;
import com.smarthealthfinance.cards.domain.repository.CardInvoiceRepository;
import com.smarthealthfinance.cards.domain.service.CreditAllocator;
import com.smarthealthfinance.cards.domain.service.CreditAllocator.Allocation;
import com.smarthealthfinance.cards.domain.service.CreditAllocator.AppliedCredit;
import com.smarthealthfinance.cards.domain.service.CreditAllocator.Charge;
import com.smarthealthfinance.cards.domain.service.CreditAllocator.Credit;
import com.smarthealthfinance.cards.domain.service.InvoiceCalendar;
import com.smarthealthfinance.cards.domain.service.InvoiceLedger;
import com.smarthealthfinance.cards.domain.valueobject.CardId;
import com.smarthealthfinance.cards.domain.valueobject.CardInvoiceId;
import com.smarthealthfinance.cards.domain.valueobject.InvoiceBalance;
import com.smarthealthfinance.cards.domain.valueobject.InvoiceDates;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.Money;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Monta o razão de um ou de vários cartões (ADR-0026 §7 e §10): aplica os estornos às parcelas
 * ({@link CreditAllocator}), soma por fatura e calcula estados e utilização ({@link InvoiceLedger}). A fatura atual
 * entra mesmo sem lançamentos. O cálculo é do domínio; aqui só se juntam as entradas.
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
                reader.facts(card.workspaceId(), card.id()), today);
    }

    /** Uma consulta de faturas e três de fatos para todos os cartões (sem N+1). */
    public Map<CardId, CardLedgerSnapshot> loadAll(WorkspaceId workspaceId, List<CreditCard> cards, LocalDate today) {
        Map<CardId, List<CardInvoice>> rowsByCard = new LinkedHashMap<>();
        for (CardInvoice row : invoices.findByWorkspace(workspaceId)) {
            rowsByCard.computeIfAbsent(row.cardId(), ignored -> new ArrayList<>()).add(row);
        }
        Map<CardId, CardFacts> facts = reader.factsByCard(workspaceId);

        Map<CardId, CardLedgerSnapshot> snapshots = new LinkedHashMap<>();
        for (CreditCard card : cards) {
            snapshots.put(card.id(), snapshot(card, rowsByCard.getOrDefault(card.id(), List.of()),
                    facts.getOrDefault(card.id(), new CardFacts(List.of(), List.of(), List.of())), today));
        }
        return snapshots;
    }

    private static CardLedgerSnapshot snapshot(CreditCard card, List<CardInvoice> rows, CardFacts facts,
                                               LocalDate today) {
        Money zero = zero(card);
        InvoiceCalendar calendar = new InvoiceCalendar(card.cycle(), rows.stream().map(CardInvoice::dates).toList());
        InvoiceDates current = calendar.invoiceOf(today);

        Map<CardInvoiceId, YearMonth> monthOfInvoice = new HashMap<>();
        rows.forEach(row -> monthOfInvoice.put(row.id(), row.dates().closingMonth()));

        List<Charge> charges = new ArrayList<>();
        for (InstallmentFact fact : facts.installments()) {
            YearMonth month = monthOfInvoice.get(fact.invoiceId());
            if (month == null) {
                throw new IllegalStateException("Parcela aponta para fatura de outro cartão");
            }
            charges.add(new Charge(fact.transactionId(), fact.number(), month, fact.amount()));
        }
        List<Credit> credits = facts.credits().stream()
                .map(credit -> new Credit(credit.transactionId(), credit.occurredOn(), credit.amount(),
                        credit.refundOfTransactionId()))
                .toList();
        Allocation allocation = CreditAllocator.allocate(charges, credits, calendar);

        // Faturas do razão: as persistidas, as que receberam crédito e a atual; somas por mês de fechamento. As
        // anteriores ao início do acompanhamento ficam de fora (ADR-0025 §15); o estorno continua aplicado a todas as
        // parcelas, porque a compra é uma só.
        YearMonth trackingStart = card.trackingStartMonth();
        Map<YearMonth, Money[]> sums = new TreeMap<>();
        rows.forEach(row -> sums.put(row.dates().closingMonth(), new Money[] {zero, zero}));
        sums.putIfAbsent(current.closingMonth(), new Money[] {zero, zero});
        for (Charge charge : charges) {
            Money[] month = sums.get(charge.closingMonth());
            month[0] = month[0].plus(allocation.netOf(charge));
        }
        for (AppliedCredit applied : allocation.credits()) {
            Money[] month = sums.computeIfAbsent(applied.closingMonth(), ignored -> new Money[] {zero, zero});
            month[1] = month[1].plus(applied.remainder());
        }
        sums.keySet().removeIf(month -> month.isBefore(trackingStart));

        List<InvoiceBalance> balances = new ArrayList<>();
        sums.forEach((month, values) -> balances.add(new InvoiceBalance(calendar.datesOf(month), values[0],
                values[1])));

        InvoiceLedger ledger = InvoiceLedger.compute(balances, trackedPayments(facts, calendar, trackingStart, zero),
                today);
        return new CardLedgerSnapshot(card, calendar, List.copyOf(rows), facts, List.copyOf(charges), allocation,
                ledger, current, today);
    }

    /**
     * Pagamentos que entram na alocação: os posteriores ao vencimento da última fatura anterior ao início do
     * acompanhamento. Os demais pagaram essa fatura, que está fora do razão (ADR-0025 §15).
     */
    static Money trackedPayments(CardFacts facts, InvoiceCalendar calendar, YearMonth trackingStart, Money zero) {
        LocalDate cutoff = calendar.datesOf(trackingStart.minusMonths(1)).dueDate();
        return facts.payments().stream()
                .filter(payment -> payment.occurredOn().isAfter(cutoff))
                .map(PaymentFact::amount)
                .reduce(zero, Money::plus);
    }

    private static Money zero(CreditCard card) {
        return Money.zero(card.limit().value().currency());
    }
}
