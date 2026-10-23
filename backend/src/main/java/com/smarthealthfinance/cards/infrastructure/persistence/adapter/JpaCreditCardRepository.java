package com.smarthealthfinance.cards.infrastructure.persistence.adapter;

import com.smarthealthfinance.cards.domain.model.CreditCard;
import com.smarthealthfinance.cards.domain.repository.CreditCardRepository;
import com.smarthealthfinance.cards.domain.valueobject.CardId;
import com.smarthealthfinance.cards.infrastructure.persistence.entity.CreditCardJpaEntity;
import com.smarthealthfinance.cards.infrastructure.persistence.repository.CreditCardJpaRepository;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import org.springframework.stereotype.Repository;

import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

@Repository
public class JpaCreditCardRepository implements CreditCardRepository {

    private final CreditCardJpaRepository jpa;

    public JpaCreditCardRepository(CreditCardJpaRepository jpa) {
        this.jpa = jpa;
    }

    /** Sempre filtrado pelo Workspace: cartão de outro Workspace é indistinguível de inexistente. */
    @Override
    public Optional<CreditCard> findById(WorkspaceId workspaceId, CardId cardId) {
        return jpa.findByIdAndWorkspaceId(cardId.value(), workspaceId.value()).map(CreditCardJpaEntity::toDomain);
    }

    @Override
    public List<CreditCard> findAllByWorkspace(WorkspaceId workspaceId) {
        return jpa.findAllByWorkspaceIdOrderByCreatedAtAscIdAsc(workspaceId.value()).stream()
                .map(CreditCardJpaEntity::toDomain)
                .toList();
    }

    @Override
    public void add(CreditCard card) {
        jpa.save(CreditCardJpaEntity.from(card));
    }

    /** O version vindo do domínio faz o merge falhar com OptimisticLockingFailureException se estiver obsoleto. */
    @Override
    public void save(CreditCard card) {
        jpa.save(CreditCardJpaEntity.from(card));
    }

    @Override
    public boolean recedeTrackingStart(WorkspaceId workspaceId, CardId cardId, YearMonth month) {
        return jpa.recedeTrackingStart(workspaceId.value(), cardId.value(), month.atDay(1)) == 1;
    }
}
