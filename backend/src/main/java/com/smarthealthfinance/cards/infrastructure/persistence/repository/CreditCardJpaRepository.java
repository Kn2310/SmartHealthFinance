package com.smarthealthfinance.cards.infrastructure.persistence.repository;

import com.smarthealthfinance.cards.infrastructure.persistence.entity.CreditCardJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CreditCardJpaRepository extends JpaRepository<CreditCardJpaEntity, UUID> {

    Optional<CreditCardJpaEntity> findByIdAndWorkspaceId(UUID id, UUID workspaceId);

    List<CreditCardJpaEntity> findAllByWorkspaceIdOrderByCreatedAtAscIdAsc(UUID workspaceId);
}
