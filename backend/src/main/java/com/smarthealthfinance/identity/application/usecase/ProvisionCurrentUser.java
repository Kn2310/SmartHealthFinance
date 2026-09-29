package com.smarthealthfinance.identity.application.usecase;

import com.smarthealthfinance.identity.application.dto.AuthenticatedIdentity;
import com.smarthealthfinance.identity.application.dto.UserView;
import com.smarthealthfinance.identity.application.exception.IncompleteIdentityClaimsException;
import com.smarthealthfinance.identity.application.port.AuthenticatedIdentityProvider;
import com.smarthealthfinance.identity.domain.model.User;
import com.smarthealthfinance.identity.domain.model.Workspace;
import com.smarthealthfinance.identity.domain.repository.UserRepository;
import com.smarthealthfinance.identity.domain.repository.WorkspaceRepository;
import com.smarthealthfinance.identity.domain.valueobject.DisplayName;
import com.smarthealthfinance.identity.domain.valueobject.Email;
import com.smarthealthfinance.identity.domain.valueobject.UserId;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import com.smarthealthfinance.shared.domain.InvalidValueException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

@Service
public class ProvisionCurrentUser {

    private static final Logger log = LoggerFactory.getLogger(ProvisionCurrentUser.class);

    private final AuthenticatedIdentityProvider identityProvider;
    private final UserRepository users;
    private final WorkspaceRepository workspaces;
    private final Clock clock;

    public ProvisionCurrentUser(AuthenticatedIdentityProvider identityProvider, UserRepository users,
                                WorkspaceRepository workspaces, Clock clock) {
        this.identityProvider = identityProvider;
        this.users = users;
        this.workspaces = workspaces;
        this.clock = clock;
    }

    /** {@code created} refere-se ao usuário; o Workspace pessoal é garantido em ambos os casos. */
    public record Result(UserView user, UUID workspaceId, boolean created) {}

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

            return new Result(UserView.from(user), ensurePersonalWorkspace(user.id(), now).id().value(), false);
        }

        User user = User.provision(UserId.generate(now), identity.externalIdentity(), email, displayNameFrom(identity, email), now);

        if (!users.addIfAbsent(user)) {

            User winner = users.findByExternalIdentity(identity.externalIdentity()).orElseThrow();
            return new Result(UserView.from(winner), ensurePersonalWorkspace(winner.id(), now).id().value(), false);
        }

        log.info("User provisioned userId={}", user.id());

        return new Result(UserView.from(user), ensurePersonalWorkspace(user.id(), now).id().value(), true);
    }

    /** Idempotente: cria o Workspace pessoal (com membership OWNER) apenas se ainda não existir. */
    private Workspace ensurePersonalWorkspace(UserId ownerId, Instant now) {
        Optional<Workspace> existing = workspaces.findPersonalByOwner(ownerId);

        if (existing.isPresent()) {
            return existing.get();
        }

        Workspace workspace = Workspace.createPersonal(WorkspaceId.generate(now), ownerId, now);

        if (!workspaces.addPersonalIfAbsent(workspace)) {
            return workspaces.findPersonalByOwner(ownerId).orElseThrow();
        }

        log.info("Personal workspace created workspaceId={} userId={}", workspace.id(), ownerId);

        return workspace;
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
