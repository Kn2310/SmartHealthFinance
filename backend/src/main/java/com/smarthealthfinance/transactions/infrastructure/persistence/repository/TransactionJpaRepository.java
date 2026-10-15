package com.smarthealthfinance.transactions.infrastructure.persistence.repository;

import com.smarthealthfinance.transactions.infrastructure.persistence.entity.TransactionJpaEntity;
import com.smarthealthfinance.transactions.domain.enums.CategorySource;
import com.smarthealthfinance.transactions.domain.enums.TransactionType;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
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

    @Query("""
            select t from TransactionJpaEntity t
            where t.workspaceId = :workspaceId and t.type in :types and t.id > :after
              and (t.categorySource is null or t.merchantKey is null)
            order by t.id
            """)
    List<TransactionJpaEntity> findAwaitingAutomation(@Param("workspaceId") UUID workspaceId,
            @Param("types") List<TransactionType> types, @Param("after") UUID after, Pageable page);

    @Query("""
            select distinct t.workspaceId from TransactionJpaEntity t
            where t.merchantKey is not null and t.merchantNormalizerVersion <> :version
            """)
    List<UUID> findWorkspacesWithOutdatedMerchants(@Param("version") String version);

    @Query("""
            select t from TransactionJpaEntity t
            where t.workspaceId = :workspaceId and t.merchantKey is not null
              and t.merchantNormalizerVersion <> :version and t.id > :after
            order by t.id
            """)
    List<TransactionJpaEntity> findWithOutdatedMerchant(@Param("workspaceId") UUID workspaceId,
            @Param("version") String version, @Param("after") UUID after, Pageable page);

    @Query("""
            select count(t) > 0 from TransactionJpaEntity t
            where t.workspaceId = :workspaceId and t.merchantKey = :merchantKey and t.type in :types
            """)
    boolean merchantKeyInUse(@Param("workspaceId") UUID workspaceId, @Param("merchantKey") String merchantKey,
            @Param("types") List<TransactionType> types);

    @Query("""
            select t from TransactionJpaEntity t
            where t.workspaceId = :workspaceId and t.merchantKey = :merchantKey and t.type in :types
              and (t.categorySource is null or t.categorySource <> :manual)
            order by t.id
            """)
    List<TransactionJpaEntity> findAutomatableByMerchant(@Param("workspaceId") UUID workspaceId,
            @Param("merchantKey") String merchantKey, @Param("types") List<TransactionType> types,
            @Param("manual") CategorySource manual);
}
