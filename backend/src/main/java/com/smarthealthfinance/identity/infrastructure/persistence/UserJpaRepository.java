package com.smarthealthfinance.identity.infrastructure.persistence;

import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface UserJpaRepository extends JpaRepository<UserJpaEntity, UUID> {

    Optional<UserJpaEntity> findByOidcIssuerAndOidcSubject(String oidcIssuer, String oidcSubject);

    @Modifying
    @Query(value = """
			insert into users (id, oidc_issuer, oidc_subject, email, display_name, status,
			                   created_at, updated_at, version)
			values (:id, :issuer, :subject, :email, :displayName, :status, :createdAt, :updatedAt, 0)
			on conflict (oidc_issuer, oidc_subject) do nothing
			""", nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("issuer") String issuer, @Param("subject") String subject,
                       @Param("email") String email, @Param("displayName") String displayName, @Param("status") String status,
                       @Param("createdAt") Instant createdAt, @Param("updatedAt") Instant updatedAt);
}
