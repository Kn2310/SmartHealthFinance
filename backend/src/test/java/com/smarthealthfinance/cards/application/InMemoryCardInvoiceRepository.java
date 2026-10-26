package com.smarthealthfinance.cards.application;

import com.smarthealthfinance.cards.domain.model.CardInvoice;
import com.smarthealthfinance.cards.domain.repository.CardInvoiceRepository;
import com.smarthealthfinance.cards.domain.valueobject.CardId;
import com.smarthealthfinance.cards.domain.valueobject.CardInvoiceId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fake das datas de fatura: filtra por Workspace e ordena como o adapter JDBC. */
public final class InMemoryCardInvoiceRepository implements CardInvoiceRepository {

    private final Map<CardInvoiceId, CardInvoice> invoices = new LinkedHashMap<>();

    public CardInvoice get(CardInvoiceId id) {
        return invoices.get(id);
    }

    @Override
    public List<CardInvoice> findByCard(WorkspaceId workspaceId, CardId cardId) {
        return invoices.values().stream()
                .filter(invoice -> invoice.workspaceId().equals(workspaceId) && invoice.cardId().equals(cardId))
                .sorted(Comparator.comparing(invoice -> invoice.dates().closingMonth()))
                .toList();
    }

    @Override
    public List<CardInvoice> findByWorkspace(WorkspaceId workspaceId) {
        return invoices.values().stream()
                .filter(invoice -> invoice.workspaceId().equals(workspaceId))
                .sorted(Comparator.comparing((CardInvoice invoice) -> invoice.cardId().value())
                        .thenComparing(invoice -> invoice.dates().closingMonth()))
                .toList();
    }

    @Override
    public void add(CardInvoice invoice) {
        if (invoices.putIfAbsent(invoice.id(), invoice) != null) {
            throw new IllegalStateException("add() só insere faturas novas");
        }
    }

    @Override
    public void updateDates(List<CardInvoice> changed) {
        changed.forEach(invoice -> invoices.computeIfPresent(invoice.id(),
                (id, current) -> current.withDates(invoice.dates())));
    }

    @Override
    public void updateStatement(CardInvoice invoice) {
        invoices.computeIfPresent(invoice.id(), (id, current) -> invoice);
    }

    @Override
    public void deleteAll(List<CardInvoice> deleted) {
        deleted.forEach(invoice -> invoices.remove(invoice.id()));
    }

    @Override
    public List<CardInvoice> lockPendingClosed(LocalDate today, int limit) {
        return invoices.values().stream()
                .filter(invoice -> invoice.closedNotifiedAt() == null && !invoice.dates().closingDate().isAfter(today))
                .sorted(Comparator.comparing(invoice -> invoice.dates().closingDate()))
                .limit(limit)
                .toList();
    }

    @Override
    public void markClosedNotified(CardInvoice invoice, Instant notifiedAt) {
        invoices.computeIfPresent(invoice.id(), (id, current) -> new CardInvoice(current.id(), current.workspaceId(),
                current.cardId(), current.dates(), current.minimumPayment(), notifiedAt));
    }
}
