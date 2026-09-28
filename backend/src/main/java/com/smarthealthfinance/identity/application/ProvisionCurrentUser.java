package com.smarthealthfinance.identity.application;

import com.smarthealthfinance.identity.domain.*;
import com.smarthealthfinance.shared.domain.InvalidValueException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.stream.Stream;

@Service
public class ProvisionCurrentUser {

    private static final Logger log = LoggerFactory.getLogger(ProvisionCurrentUser.class);

    private final AuthenticatedIdentityProvider identityProvider;
    private final UserRepository users;
    private final Clock clock;

    public ProvisionCurrentUser(AuthenticatedIdentityProvider identityProvider, UserRepository users, Clock clock) {
        this.identityProvider = identityProvider;
        this.users = users;
        this.clock = clock;
    }

    public record Result(UserView user, boolean created) {}

    @Transactional
    public Result execute() {
        AuthenticatedIdentity identity = identityProvider.current();
        Email email = emailFrom(identity);
        Instant now = clock.instant();

        Optional<User> existing = users.findByExternalIdentity(identity.externalIdentity());

        if (existing.isPresent()) {
            User user = existing.get();

            if (user.syncEmail(email, now)) {
                users.save(user);
            }

            return new Result(UserView.from(user), false);
        }

        User user = User.provision(UserId.generate(now), identity.externalIdentity(), email, displayNameFrom(identity, email), now);

        if (!users.addIfAbsent(user)) {

            User winner = users.findByExternalIdentity(identity.externalIdentity()).orElseThrow();
            return new Result(UserView.from(winner), false);
        }

        log.info("User provisioned userId={}", user.id());

        return new Result(UserView.from(user), true);
    }

    private static Email emailFrom(AuthenticatedIdentity identity) {
        try {
            return new Email(identity.email());
        }
        catch (InvalidValueException ex) {
            throw new IncompleteIdentityClaimsException("email");
        }
    }

    private static DisplayName displayNameFrom(AuthenticatedIdentity identity, Email email) {
        String candidate = Stream.of(identity.name(), identity.preferredUsername())
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(email.value().substring(0, email.value().indexOf('@')));

        String stripped = candidate.strip();

        return new DisplayName(stripped.length() > DisplayName.MAX_LENGTH
                ? stripped.substring(0, DisplayName.MAX_LENGTH) : stripped);
    }
}
