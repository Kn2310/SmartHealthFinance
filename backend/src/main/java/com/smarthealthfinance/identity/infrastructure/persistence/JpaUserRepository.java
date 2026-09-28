package com.smarthealthfinance.identity.infrastructure.persistence;

import com.smarthealthfinance.identity.domain.ExternalIdentity;
import com.smarthealthfinance.identity.domain.User;
import com.smarthealthfinance.identity.domain.UserId;
import com.smarthealthfinance.identity.domain.UserRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class JpaUserRepository implements UserRepository {

    private final UserJpaRepository jpa;

    public JpaUserRepository(UserJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<User> findById(UserId id) {
        return jpa.findById(id.value()).map(UserJpaEntity::toDomain);
    }

    @Override
    public Optional<User> findByExternalIdentity(ExternalIdentity externalIdentity) {
        return jpa.findByOidcIssuerAndOidcSubject(externalIdentity.issuer(), externalIdentity.subject()).map(UserJpaEntity::toDomain);
    }

    @Override
    public boolean addIfAbsent(User user) {
        return jpa.insertIfAbsent(
                user.id().value(), user.externalIdentity().issuer(), user.externalIdentity().subject(), user.email().value(), user.displayName().value(),
                user.status().name(), user.createdAt(), user.updatedAt()
            ) == 1;
    }

    @Override
    public void save(User user) {
        jpa.save(UserJpaEntity.from(user));
    }
}
