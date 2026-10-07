package com.smarthealthfinance.transactions.infrastructure.persistence.repository;

import com.smarthealthfinance.transactions.infrastructure.persistence.entity.TransactionJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransactionJpaRepository extends JpaRepository<TransactionJpaEntity, UUID>,
        JpaSpecificationExecutor<TransactionJpaEntity> {

    Optional<TransactionJpaEntity> findByIdAndWorkspaceId(UUID id, UUID workspaceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TransactionJpaEntity t where t.id = :id and t.workspaceId = :workspaceId")
    Optional<TransactionJpaEntity> findForUpdate(@Param("id") UUID id, @Param("workspaceId") UUID workspaceId);

    List<TransactionJpaEntity> findAllByWorkspaceIdAndRefundOfTransactionId(UUID workspaceId, UUID refundOfTransactionId);
}
