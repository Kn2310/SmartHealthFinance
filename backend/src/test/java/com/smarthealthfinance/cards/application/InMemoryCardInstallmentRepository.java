package com.smarthealthfinance.cards.application;

import com.smarthealthfinance.cards.domain.model.CardInstallment;
import com.smarthealthfinance.cards.domain.repository.CardInstallmentRepository;
import com.smarthealthfinance.cards.domain.valueobject.CardId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Fake das parcelas: filtra por Workspace e ordena como o adapter JDBC. */
public final class InMemoryCardInstallmentRepository implements CardInstallmentRepository {

    private final List<CardInstallment> installments = new ArrayList<>();

    public List<CardInstallment> all() {
        return List.copyOf(installments);
    }

    @Override
    public void addAll(List<CardInstallment> added) {
        installments.addAll(added);
    }

    @Override
    public List<CardInstallment> findByTransaction(WorkspaceId workspaceId, UUID transactionId) {
        return installments.stream()
                .filter(i -> i.workspaceId().equals(workspaceId) && i.transactionId().equals(transactionId))
                .sorted(Comparator.comparingInt(CardInstallment::number))
                .toList();
    }

    @Override
    public List<CardInstallment> findByCard(WorkspaceId workspaceId, CardId cardId) {
        return installments.stream()
                .filter(i -> i.workspaceId().equals(workspaceId) && i.cardId().equals(cardId))
                .sorted(Comparator.comparing(CardInstallment::purchasedOn)
                        .thenComparing(i -> i.transactionId().toString())
                        .thenComparingInt(CardInstallment::number))
                .toList();
    }

    @Override
    public void updateInvoices(List<CardInstallment> moved) {
        for (CardInstallment changed : moved) {
            installments.replaceAll(current -> current.workspaceId().equals(changed.workspaceId())
                    && current.transactionId().equals(changed.transactionId())
                    && current.number() == changed.number() ? changed : current);
        }
    }
}
