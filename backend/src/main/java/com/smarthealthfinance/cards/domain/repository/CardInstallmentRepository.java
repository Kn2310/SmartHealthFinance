package com.smarthealthfinance.cards.domain.repository;

import com.smarthealthfinance.cards.domain.model.CardInstallment;
import com.smarthealthfinance.cards.domain.valueobject.CardId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;

import java.util.List;
import java.util.UUID;

public interface CardInstallmentRepository {

    void addAll(List<CardInstallment> installments);

    /** Parcelas da compra, por número. */
    List<CardInstallment> findByTransaction(WorkspaceId workspaceId, UUID transactionId);

    /** Todas as parcelas do cartão, de qualquer status de transação (reatribuição na mudança de ciclo). */
    List<CardInstallment> findByCard(WorkspaceId workspaceId, CardId cardId);

    /** Muda a fatura (e a versão das regras) das parcelas reatribuídas. */
    void updateInvoices(List<CardInstallment> installments);
}
