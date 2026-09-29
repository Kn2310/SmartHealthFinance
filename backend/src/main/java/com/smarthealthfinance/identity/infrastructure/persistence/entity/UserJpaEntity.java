package com.smarthealthfinance.identity.infrastructure.persistence.entity;

import com.smarthealthfinance.identity.domain.enums.UserStatus;
import com.smarthealthfinance.identity.domain.model.User;
import com.smarthealthfinance.identity.domain.valueobject.DisplayName;
import com.smarthealthfinance.identity.domain.valueobject.Email;
import com.smarthealthfinance.identity.domain.valueobject.ExternalIdentity;
import com.smarthealthfinance.identity.domain.valueobject.UserId;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class UserJpaEntity {
    @Id
    private UUID id;

    @Column(name = "oidc_issuer", nullable = false, updatable = false)
    private String oidcIssuer;

    @Column(name = "oidc_subject", nullable = false, updatable = false)
    private String oidcSubject;

    @Column(nullable = false)
    private String email;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected UserJpaEntity() {
    }

    public static UserJpaEntity from(User user) {
        UserJpaEntity entity = new UserJpaEntity();

        entity.id = user.id().value();
        entity.oidcIssuer = user.externalIdentity().issuer();
        entity.oidcSubject = user.externalIdentity().subject();
        entity.email = user.email().value();
        entity.displayName = user.displayName().value();
        entity.status = user.status();
        entity.createdAt = user.createdAt();
        entity.updatedAt = user.updatedAt();
        entity.version = user.version();

        return entity;
    }

    public User toDomain() {
        return User.restore(new UserId(id), new ExternalIdentity(oidcIssuer, oidcSubject), new Email(email),
                new DisplayName(displayName), status, createdAt, updatedAt, version);
    }
}
