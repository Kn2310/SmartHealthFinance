package com.smarthealthfinance.cards.domain.repository;

import com.smarthealthfinance.cards.domain.model.CreditCard;
import com.smarthealthfinance.cards.domain.valueobject.CardId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;

import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

public interface CreditCardRepository {

    Optional<CreditCard> findById(WorkspaceId workspaceId, CardId cardId);

    /** Em ordem de criação. */
    List<CreditCard> findAllByWorkspace(WorkspaceId workspaceId);

    void add(CreditCard card);

    void save(CreditCard card);

    /**
     * Recua o início do acompanhamento até o mês de fechamento informado; nunca avança (ADR-0025 §15). Não passa pelo
     * agregado nem pela versão dele: um {@link #save} concorrente não o desfaz.
     *
     * @return true se mudou
     */
    boolean recedeTrackingStart(WorkspaceId workspaceId, CardId cardId, YearMonth month);
}
