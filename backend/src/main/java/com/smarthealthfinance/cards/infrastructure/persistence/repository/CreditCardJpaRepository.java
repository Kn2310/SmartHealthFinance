package com.smarthealthfinance.cards.infrastructure.persistence.repository;

import com.smarthealthfinance.cards.infrastructure.persistence.entity.CreditCardJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CreditCardJpaRepository extends JpaRepository<CreditCardJpaEntity, UUID> {

    Optional<CreditCardJpaEntity> findByIdAndWorkspaceId(UUID id, UUID workspaceId);

    List<CreditCardJpaEntity> findAllByWorkspaceIdOrderByCreatedAtAscIdAsc(UUID workspaceId);

    /**
     * Só recua (ADR-0025 §15), sem tocar na versão do agregado: não conflita com uma edição concorrente.
     *
     * @return 1 se mudou
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            update credit_cards set tracking_start_month = :month
            where id = :id and workspace_id = :workspaceId and tracking_start_month > :month
            """, nativeQuery = true)
    int recedeTrackingStart(@Param("workspaceId") UUID workspaceId, @Param("id") UUID id,
                            @Param("month") LocalDate month);
}
