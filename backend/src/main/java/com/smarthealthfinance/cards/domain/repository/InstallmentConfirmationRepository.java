package com.smarthealthfinance.cards.domain.repository;

import com.smarthealthfinance.cards.domain.valueobject.CardId;
import com.smarthealthfinance.cards.domain.valueobject.InstallmentKey;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;

import java.time.Instant;
import java.util.Collection;
import java.util.Set;

/**
 * Parcelas já confirmadas por uma linha de fatura importada (ADR-0009 §26): cada parcela é conciliada no máximo uma
 * vez. A garantia final é a chave primária.
 */
public interface InstallmentConfirmationRepository {

    Set<InstallmentKey> findByCard(WorkspaceId workspaceId, CardId cardId);

    void addAll(WorkspaceId workspaceId, CardId cardId, Collection<InstallmentKey> confirmed, Instant now);
}
