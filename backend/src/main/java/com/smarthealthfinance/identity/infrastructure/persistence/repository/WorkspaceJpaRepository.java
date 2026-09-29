package com.smarthealthfinance.identity.infrastructure.persistence.repository;

import com.smarthealthfinance.identity.domain.enums.WorkspaceKind;
import com.smarthealthfinance.identity.infrastructure.persistence.entity.WorkspaceJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkspaceJpaRepository extends JpaRepository<WorkspaceJpaEntity, UUID> {

    Optional<WorkspaceJpaEntity> findByKindAndOwnerUserId(WorkspaceKind kind, UUID ownerUserId);

    /** Join sem fetch: filtra os Workspaces sem truncar a coleção de memberships carregada. */
    @Query("""
            select w from WorkspaceJpaEntity w join w.memberships m
            where m.userId = :userId
            order by w.createdAt, w.id
            """)
    List<WorkspaceJpaEntity> findAllByMember(@Param("userId") UUID userId);

    @Modifying
    @Query(value = """
            insert into workspaces (id, kind, owner_user_id, name, base_currency, created_at, updated_at, version)
            values (:id, 'PERSONAL', :ownerId, :name, :baseCurrency, :createdAt, :updatedAt, 0)
            on conflict (owner_user_id) where kind = 'PERSONAL' do nothing
            """, nativeQuery = true)
    int insertPersonalIfAbsent(@Param("id") UUID id, @Param("ownerId") UUID ownerId, @Param("name") String name,
                               @Param("baseCurrency") String baseCurrency, @Param("createdAt") Instant createdAt,
                               @Param("updatedAt") Instant updatedAt);

    @Modifying
    @Query(value = """
            insert into workspace_memberships (workspace_id, user_id, role, joined_at)
            values (:workspaceId, :userId, :role, :joinedAt)
            """, nativeQuery = true)
    int insertMembership(@Param("workspaceId") UUID workspaceId, @Param("userId") UUID userId,
                         @Param("role") String role, @Param("joinedAt") Instant joinedAt);
}
