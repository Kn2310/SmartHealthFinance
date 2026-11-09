package com.smarthealthfinance.consents.infrastructure.persistence.entity;

import com.smarthealthfinance.consents.domain.enums.ConsentPurpose;
import com.smarthealthfinance.consents.domain.enums.ConsentStatus;
import com.smarthealthfinance.consents.domain.model.Consent;
import com.smarthealthfinance.consents.domain.valueobject.ConsentId;
import com.smarthealthfinance.identity.domain.valueobject.UserId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "consents")
public class ConsentJpaEntity {

    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private ConsentPurpose purpose;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ConsentStatus status;

    @Column(name = "terms_version", nullable = false)
    private String termsVersion;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected ConsentJpaEntity() {
    }

    public static ConsentJpaEntity from(Consent consent) {
        ConsentJpaEntity entity = new ConsentJpaEntity();
        entity.id = consent.id().value();
        entity.workspaceId = consent.workspaceId().value();
        entity.userId = consent.userId().value();
        entity.purpose = consent.purpose();
        entity.status = consent.status();
        entity.termsVersion = consent.termsVersion();
        entity.grantedAt = consent.grantedAt();
        entity.revokedAt = consent.revokedAt().orElse(null);
        entity.createdAt = consent.createdAt();
        entity.updatedAt = consent.updatedAt();
        entity.version = consent.version();
        return entity;
    }

    public Consent toDomain() {
        return Consent.restore(new ConsentId(id), new WorkspaceId(workspaceId), new UserId(userId), purpose, status,
                termsVersion, grantedAt, revokedAt, createdAt, updatedAt, version);
    }
}
