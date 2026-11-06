package com.smarthealthfinance.goals.infrastructure.persistence.repository;

import com.smarthealthfinance.goals.domain.enums.GoalStatus;
import com.smarthealthfinance.goals.domain.enums.GoalType;
import com.smarthealthfinance.goals.infrastructure.persistence.entity.GoalJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GoalJpaRepository extends JpaRepository<GoalJpaEntity, UUID> {

    Optional<GoalJpaEntity> findByIdAndWorkspaceId(UUID id, UUID workspaceId);

    List<GoalJpaEntity> findAllByWorkspaceIdOrderByCreatedAtDescIdDesc(UUID workspaceId);

    Optional<GoalJpaEntity> findFirstByWorkspaceIdAndDestinationAccountIdAndStatusIn(UUID workspaceId,
            UUID destinationAccountId, Collection<GoalStatus> statuses);

    Optional<GoalJpaEntity> findFirstByWorkspaceIdAndTypeAndStatusIn(UUID workspaceId, GoalType type,
            Collection<GoalStatus> statuses);
}
