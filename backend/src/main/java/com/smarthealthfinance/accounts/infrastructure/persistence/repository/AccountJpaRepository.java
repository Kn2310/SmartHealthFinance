package com.smarthealthfinance.accounts.infrastructure.persistence.repository;

import com.smarthealthfinance.accounts.infrastructure.persistence.entity.AccountJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountJpaRepository extends JpaRepository<AccountJpaEntity, UUID> {

    Optional<AccountJpaEntity> findByIdAndWorkspaceId(UUID id, UUID workspaceId);

    List<AccountJpaEntity> findAllByWorkspaceIdOrderByCreatedAtAscIdAsc(UUID workspaceId);
}
