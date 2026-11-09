package com.smarthealthfinance.consents.infrastructure.persistence.adapter;

import com.smarthealthfinance.consents.domain.enums.ConsentPurpose;
import com.smarthealthfinance.consents.domain.model.Consent;
import com.smarthealthfinance.consents.domain.repository.ConsentRepository;
import com.smarthealthfinance.consents.infrastructure.persistence.entity.ConsentJpaEntity;
import com.smarthealthfinance.consents.infrastructure.persistence.repository.ConsentJpaRepository;
import com.smarthealthfinance.identity.domain.valueobject.UserId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class JpaConsentRepository implements ConsentRepository {

    private final ConsentJpaRepository jpa;

    public JpaConsentRepository(ConsentJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<Consent> find(WorkspaceId workspaceId, UserId userId, ConsentPurpose purpose) {
        return jpa.findByWorkspaceIdAndUserIdAndPurpose(workspaceId.value(), userId.value(), purpose)
                .map(ConsentJpaEntity::toDomain);
    }

    @Override
    public List<Consent> findAll(WorkspaceId workspaceId, UserId userId) {
        return jpa.findAllByWorkspaceIdAndUserId(workspaceId.value(), userId.value()).stream()
                .map(ConsentJpaEntity::toDomain)
                .toList();
    }

    /**
     * Duas primeiras concessões simultâneas da mesma finalidade colidem na chave única: a segunda vira o mesmo
     * conflito de concorrência de uma edição obsoleta (409 {@code CONFLICT}).
     */
    @Override
    public void add(Consent consent) {
        try {
            jpa.saveAndFlush(ConsentJpaEntity.from(consent));
        } catch (DataIntegrityViolationException e) {
            throw new OptimisticLockingFailureException("Concurrent consent grant", e);
        }
    }

    @Override
    public void save(Consent consent) {
        jpa.save(ConsentJpaEntity.from(consent));
    }
}
