package com.smarthealthfinance.consents.infrastructure.persistence.repository;

import com.smarthealthfinance.consents.domain.enums.ConsentPurpose;
import com.smarthealthfinance.consents.infrastructure.persistence.entity.ConsentJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConsentJpaRepository extends JpaRepository<ConsentJpaEntity, UUID> {

    Optional<ConsentJpaEntity> findByWorkspaceIdAndUserIdAndPurpose(UUID workspaceId, UUID userId,
                                                                    ConsentPurpose purpose);

    List<ConsentJpaEntity> findAllByWorkspaceIdAndUserId(UUID workspaceId, UUID userId);
}
