package com.smarthealthfinance.cards.application;

import com.smarthealthfinance.cards.application.port.CardLedgerReader;
import com.smarthealthfinance.cards.domain.valueobject.CardId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.Money;

import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fatos {@code POSTED} informados pelo teste, por Workspace e cartão. */
public final class FakeCardLedgerReader implements CardLedgerReader {

    private static final Money ZERO = Money.zero(Currency.getInstance("BRL"));

    private final Map<CardId, WorkspaceId> owners = new LinkedHashMap<>();
    private final Map<CardId, List<InstallmentFact>> installments = new HashMap<>();
    private final Map<CardId, List<CreditFact>> credits = new HashMap<>();
    private final Map<CardId, Money> payments = new HashMap<>();

    public void installment(WorkspaceId workspace, CardId card, InstallmentFact fact) {
        owners.put(card, workspace);
        installments.computeIfAbsent(card, ignored -> new ArrayList<>()).add(fact);
    }

    public void credit(WorkspaceId workspace, CardId card, CreditFact fact) {
        owners.put(card, workspace);
        credits.computeIfAbsent(card, ignored -> new ArrayList<>()).add(fact);
    }

    public void pay(WorkspaceId workspace, CardId card, Money amount) {
        owners.put(card, workspace);
        payments.merge(card, amount, Money::plus);
    }

    @Override
    public CardFacts facts(WorkspaceId workspaceId, CardId cardId) {
        return factsByCard(workspaceId).getOrDefault(cardId, new CardFacts(List.of(), List.of(), ZERO));
    }

    @Override
    public Map<CardId, CardFacts> factsByCard(WorkspaceId workspaceId) {
        Map<CardId, CardFacts> facts = new LinkedHashMap<>();
        owners.forEach((card, owner) -> {
            if (owner.equals(workspaceId)) {
                facts.put(card, new CardFacts(List.copyOf(installments.getOrDefault(card, List.of())),
                        List.copyOf(credits.getOrDefault(card, List.of())), payments.getOrDefault(card, ZERO)));
            }
        });
        return facts;
    }
}
