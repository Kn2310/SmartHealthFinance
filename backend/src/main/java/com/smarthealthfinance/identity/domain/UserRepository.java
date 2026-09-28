package com.smarthealthfinance.identity.domain;

import java.util.Optional;

public interface UserRepository {

    Optional<User> findById(UserId id);

    Optional<User> findByExternalIdentity(ExternalIdentity externalIdentity);

    /**
     * Insere se ainda não existir usuário para (issuer, subject). Idempotente e seguro sob concorrência.
     * @return false se outra requisição provisionou primeiro
     */
    boolean addIfAbsent(User user);

    void save(User user);
}
