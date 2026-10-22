package com.smarthealthfinance.cards.application;

import com.smarthealthfinance.cards.application.port.CardLedgerReader;
import com.smarthealthfinance.cards.domain.valueobject.CardId;
import com.smarthealthfinance.cards.domain.valueobject.CardInvoiceId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.Money;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Somas por fatura e pagamentos informados pelo teste, por Workspace e cartão. */
public final class FakeCardLedgerReader implements CardLedgerReader {

    private static final Money ZERO = Money.zero(java.util.Currency.getInstance("BRL"));

    private final Map<CardId, WorkspaceId> owners = new HashMap<>();
    private final Map<CardId, Map<CardInvoiceId, InvoiceTotals>> charges = new HashMap<>();
    private final Map<CardId, Money> payments = new HashMap<>();
    private final Map<CardId, List<CommittedInstallment>> installments = new HashMap<>();

    public void charge(WorkspaceId workspace, CardId card, CardInvoiceId invoice, Money amount) {
        owners.put(card, workspace);
        charges.computeIfAbsent(card, ignored -> new LinkedHashMap<>()).put(invoice, new InvoiceTotals(amount, ZERO));
    }

    public void pay(WorkspaceId workspace, CardId card, Money amount) {
        owners.put(card, workspace);
        payments.merge(card, amount, Money::plus);
    }

    public void commit(CardId card, CommittedInstallment installment) {
        installments.computeIfAbsent(card, ignored -> new java.util.ArrayList<>()).add(installment);
    }

    @Override
    public CardTotals totals(WorkspaceId workspaceId, CardId cardId) {
        return totalsByCard(workspaceId).getOrDefault(cardId, new CardTotals(Map.of(), ZERO));
    }

    @Override
    public Map<CardId, CardTotals> totalsByCard(WorkspaceId workspaceId) {
        Map<CardId, CardTotals> totals = new LinkedHashMap<>();
        owners.forEach((card, owner) -> {
            if (owner.equals(workspaceId)) {
                totals.put(card, new CardTotals(Map.copyOf(charges.getOrDefault(card, Map.of())),
                        payments.getOrDefault(card, ZERO)));
            }
        });
        return totals;
    }

    @Override
    public ItemPage items(WorkspaceId workspaceId, CardId cardId, CardInvoiceId invoiceId, int offset, int limit) {
        return new ItemPage(List.of(), 0);
    }

    @Override
    public long itemCount(WorkspaceId workspaceId, CardId cardId, CardInvoiceId invoiceId) {
        return 0;
    }

    @Override
    public List<CommittedInstallment> installments(WorkspaceId workspaceId, CardId cardId,
                                                   Collection<CardInvoiceId> invoiceIds) {
        if (!workspaceId.equals(owners.get(cardId))) {
            return List.of();
        }
        return installments.getOrDefault(cardId, List.of()).stream()
                .filter(installment -> invoiceIds.contains(installment.invoiceId()))
                .toList();
    }
}
