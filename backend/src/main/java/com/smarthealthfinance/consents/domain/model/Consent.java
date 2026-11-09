package com.smarthealthfinance.consents.domain.model;

import com.smarthealthfinance.consents.domain.enums.ConsentPurpose;
import com.smarthealthfinance.consents.domain.enums.ConsentState;
import com.smarthealthfinance.consents.domain.enums.ConsentStatus;
import com.smarthealthfinance.consents.domain.valueobject.ConsentId;
import com.smarthealthfinance.identity.domain.valueobject.UserId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Consentimento de uma pessoa, num Workspace, para uma finalidade (ADR-0038). Só existe depois da primeira concessão:
 * a ausência é {@link ConsentState#PENDING}.
 */
public class Consent {

    private final ConsentId id;
    private final WorkspaceId workspaceId;
    private final UserId userId;
    private final ConsentPurpose purpose;
    private ConsentStatus status;
    private String termsVersion;
    private Instant grantedAt;
    private Instant revokedAt;
    private final Instant createdAt;
    private Instant updatedAt;
    private final long version;

    private Consent(ConsentId id, WorkspaceId workspaceId, UserId userId, ConsentPurpose purpose, ConsentStatus status,
                    String termsVersion, Instant grantedAt, Instant revokedAt, Instant createdAt, Instant updatedAt,
                    long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.purpose = Objects.requireNonNull(purpose, "purpose");
        this.status = Objects.requireNonNull(status, "status");
        this.termsVersion = Objects.requireNonNull(termsVersion, "termsVersion");
        this.grantedAt = Objects.requireNonNull(grantedAt, "grantedAt");
        this.revokedAt = revokedAt;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.version = version;
    }

    /** Primeira concessão, sempre na versão vigente do termo. */
    public static Consent grant(ConsentId id, WorkspaceId workspaceId, UserId userId, ConsentPurpose purpose,
                                String termsVersion, Instant now) {
        purpose.requireCurrentTerms(termsVersion);
        return new Consent(id, workspaceId, userId, purpose, ConsentStatus.ACTIVE, termsVersion, now, null, now, now,
                0);
    }

    public static Consent restore(ConsentId id, WorkspaceId workspaceId, UserId userId, ConsentPurpose purpose,
                                  ConsentStatus status, String termsVersion, Instant grantedAt, Instant revokedAt,
                                  Instant createdAt, Instant updatedAt, long version) {
        return new Consent(id, workspaceId, userId, purpose, status, termsVersion, grantedAt, revokedAt, createdAt,
                updatedAt, version);
    }

    /**
     * Concede de novo (depois de revogado ou numa versão nova do termo).
     *
     * @return false se já estava ativo nesta versão (idempotente)
     */
    public boolean regrant(String newTermsVersion, Instant now) {
        purpose.requireCurrentTerms(newTermsVersion);
        if (isActive()) {
            return false;
        }
        status = ConsentStatus.ACTIVE;
        termsVersion = newTermsVersion;
        grantedAt = now;
        revokedAt = null;
        updatedAt = now;
        return true;
    }

    /** @return false se já estava revogado (idempotente). Um consentimento expirado também é revogável. */
    public boolean revoke(Instant now) {
        if (status == ConsentStatus.REVOKED) {
            return false;
        }
        status = ConsentStatus.REVOKED;
        revokedAt = now;
        updatedAt = now;
        return true;
    }

    /** Ativo só na versão vigente do termo. */
    public boolean isActive() {
        return state() == ConsentState.ACTIVE;
    }

    public ConsentState state() {
        if (status == ConsentStatus.REVOKED) {
            return ConsentState.REVOKED;
        }
        return purpose.currentTermsVersion().equals(termsVersion) ? ConsentState.ACTIVE : ConsentState.EXPIRED;
    }

    public ConsentId id() { return id; }
    public WorkspaceId workspaceId() { return workspaceId; }
    public UserId userId() { return userId; }
    public ConsentPurpose purpose() { return purpose; }
    public ConsentStatus status() { return status; }
    public String termsVersion() { return termsVersion; }
    public Instant grantedAt() { return grantedAt; }
    public Optional<Instant> revokedAt() { return Optional.ofNullable(revokedAt); }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public long version() { return version; }
}
