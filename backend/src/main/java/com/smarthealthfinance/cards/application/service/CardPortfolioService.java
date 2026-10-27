package com.smarthealthfinance.cards.application.service;

import com.smarthealthfinance.cards.application.dto.CardLedgerSnapshot;
import com.smarthealthfinance.cards.application.dto.InvoiceItemView;
import com.smarthealthfinance.cards.application.port.CardLedgerReader;
import com.smarthealthfinance.cards.application.port.CardLedgerReader.CardFacts;
import com.smarthealthfinance.cards.application.port.CardLedgerReader.InstallmentFact;
import com.smarthealthfinance.cards.application.port.CardPortfolio;
import com.smarthealthfinance.cards.domain.enums.CardStatus;
import com.smarthealthfinance.cards.domain.enums.InvoiceStatus;
import com.smarthealthfinance.cards.domain.model.CardInvoice;
import com.smarthealthfinance.cards.domain.model.CreditCard;
import com.smarthealthfinance.cards.domain.repository.CardInvoiceRepository;
import com.smarthealthfinance.cards.domain.repository.CreditCardRepository;
import com.smarthealthfinance.cards.domain.service.CreditAllocator.Charge;
import com.smarthealthfinance.cards.domain.service.InvoiceCalendar;
import com.smarthealthfinance.cards.domain.valueobject.CardId;
import com.smarthealthfinance.cards.domain.valueobject.CardPosition;
import com.smarthealthfinance.cards.domain.valueobject.CardPosition.DueInvoice;
import com.smarthealthfinance.cards.domain.valueobject.CardPosition.InstallmentCommitment;
import com.smarthealthfinance.cards.domain.valueobject.CardPosition.InvoiceLine;
import com.smarthealthfinance.cards.domain.valueobject.InvoiceStatement;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Posição dos cartões numa data (ADR-0027 §1): o mesmo razão das leituras de fatura e utilização, calculado com "hoje"
 * = a data e só com os fatos até ela. A pontualidade de cada fatura é o razão no vencimento efetivo dela.
 */
@Service
public class CardPortfolioService implements CardPortfolio {

    /** Faturas com lançamentos detalhados: a em foco, as 3 da base do D11 e folga para a série (ADR-0028 §2). */
    static final int DETAILED_INVOICES = 6;

    private final CreditCardRepository cards;
    private final CardInvoiceRepository invoices;
    private final CardLedgerReader reader;

    public CardPortfolioService(CreditCardRepository cards, CardInvoiceRepository invoices, CardLedgerReader reader) {
        this.cards = cards;
        this.invoices = invoices;
        this.reader = reader;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<LocalDate, List<CardPosition>> positions(WorkspaceId workspaceId, Collection<LocalDate> dates) {
        Map<LocalDate, List<CardPosition>> result = new TreeMap<>();
        new TreeSet<>(dates).forEach(date -> result.put(date, new ArrayList<>()));

        List<CreditCard> active = cards.findAllByWorkspace(workspaceId).stream()
                .filter(card -> card.status() == CardStatus.ACTIVE)
                .toList();
        if (active.isEmpty() || result.isEmpty()) {
            return result;
        }

        Map<CardId, List<CardInvoice>> rowsByCard = new HashMap<>();
        for (CardInvoice row : invoices.findByWorkspace(workspaceId)) {
            rowsByCard.computeIfAbsent(row.cardId(), ignored -> new ArrayList<>()).add(row);
        }
        Map<CardId, CardFacts> facts = reader.factsByCard(workspaceId);

        for (CreditCard card : active) {
            List<CardInvoice> rows = rowsByCard.getOrDefault(card.id(), List.of());
            CardFacts cardFacts = facts.getOrDefault(card.id(), new CardFacts(List.of(), List.of(), List.of()));
            Map<LocalDate, CardLedgerSnapshot> cache = new HashMap<>();
            InvoiceCalendar calendar = new InvoiceCalendar(card.cycle(), rows.stream().map(CardInvoice::dates).toList());
            LocalDate trackedFrom = calendar.datesOf(card.trackingStartMonth()).periodStart();

            for (LocalDate date : result.keySet()) {
                if (date.isBefore(trackedFrom)) {
                    continue;
                }
                result.get(date).add(position(card, date,
                        day -> cache.computeIfAbsent(day,
                                d -> CardLedgerLoader.snapshotAsOf(card, rows, cardFacts, d))));
            }
        }
        return result;
    }

    private interface SnapshotAt {
        CardLedgerSnapshot at(LocalDate date);
    }

    static CardPosition position(CreditCard card, LocalDate date, SnapshotAt snapshots) {
        CardLedgerSnapshot snapshot = snapshots.at(date);
        List<InvoiceStatement> statements = snapshot.ledger().statements();

        List<DueInvoice> due = new ArrayList<>();
        for (InvoiceStatement statement : statements) {
            LocalDate effective = statement.dates().effectiveDueDate();
            if (statement.status() == InvoiceStatus.FUTURE || statement.status() == InvoiceStatus.OPEN
                    || !statement.amount().isPositive() || effective.isAfter(date)) {
                continue;
            }
            boolean onTime = snapshots.at(effective).ledger().statements().stream()
                    .filter(s -> s.dates().closingMonth().equals(statement.dates().closingMonth()))
                    .findFirst()
                    .map(s -> !s.remaining().isPositive())
                    .orElse(false);
            due.add(new DueInvoice(statement.dates().dueMonth(), statement.dates().dueDate(), effective,
                    statement.amount(), onTime));
        }

        Map<YearMonth, List<InvoiceLine>> lines = new HashMap<>();
        List<InvoiceStatement> started = statements.stream()
                .filter(s -> s.status() != InvoiceStatus.FUTURE)
                .toList();
        for (InvoiceStatement statement : started.subList(Math.max(0, started.size() - DETAILED_INVOICES),
                started.size())) {
            YearMonth month = statement.dates().closingMonth();
            lines.put(month, snapshot.items(month).stream().map(CardPortfolioService::line).toList());
        }

        return new CardPosition(card.id(), card.nickname().value(), card.last4().value(), card.limit().value(), date,
                snapshot.ledger().utilization(card.limit()), snapshot.currentStatement(),
                snapshot.nextInvoiceCommitted(), statements, commitments(snapshot), due, lines);
    }

    /** Parcelas que caem em faturas posteriores à atual, por compra, na ordem dos fatos. */
    static List<InstallmentCommitment> commitments(CardLedgerSnapshot snapshot) {
        YearMonth current = snapshot.current().closingMonth();
        Money zero = Money.zero(snapshot.card().limit().value().currency());
        Map<UUID, InstallmentCommitment> byPurchase = new LinkedHashMap<>();
        List<InstallmentFact> facts = snapshot.facts().installments();
        for (int i = 0; i < facts.size(); i++) {
            Charge charge = snapshot.charges().get(i);
            Money net = snapshot.allocation().netOf(charge);
            if (!charge.closingMonth().isAfter(current) || !net.isPositive()) {
                continue;
            }
            InstallmentFact fact = facts.get(i);
            YearMonth dueMonth = snapshot.calendar().datesOf(charge.closingMonth()).dueMonth();
            InstallmentCommitment known = byPurchase.get(fact.transactionId());
            byPurchase.put(fact.transactionId(), known == null
                    ? new InstallmentCommitment(fact.transactionId(), fact.merchantName(), fact.categoryCode(),
                            fact.count(), 1, zero.plus(net), dueMonth)
                    : new InstallmentCommitment(known.transactionId(), known.merchantName(), known.categoryCode(),
                            known.count(), known.remainingCount() + 1, known.remainingAmount().plus(net),
                            dueMonth.isAfter(known.lastDueMonth()) ? dueMonth : known.lastDueMonth()));
        }
        return List.copyOf(byPurchase.values());
    }

    private static InvoiceLine line(InvoiceItemView item) {
        return new InvoiceLine(item.kind(), item.transactionId(), item.installmentNumber(), item.installmentCount(),
                item.categoryCode(), item.merchantName(), item.amount().minus(item.cancelledAmount()));
    }
}
