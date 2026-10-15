package com.smarthealthfinance.accounts.application;

import com.smarthealthfinance.accounts.application.service.AccountAccess;
import com.smarthealthfinance.accounts.application.service.AccountEvents;
import com.smarthealthfinance.accounts.domain.model.Account;
import com.smarthealthfinance.identity.application.InMemoryUserRepository;
import com.smarthealthfinance.identity.application.InMemoryWorkspaceRepository;
import com.smarthealthfinance.identity.application.dto.AuthenticatedIdentity;
import com.smarthealthfinance.identity.application.service.CurrentUserService;
import com.smarthealthfinance.identity.application.service.WorkspaceAccessGuard;
import com.smarthealthfinance.identity.domain.model.User;
import com.smarthealthfinance.identity.domain.model.Workspace;
import com.smarthealthfinance.shared.application.event.IntegrationEvent;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.smarthealthfinance.identity.IdentityFixtures.activeUser;
import static com.smarthealthfinance.identity.IdentityFixtures.personalWorkspace;

/** Ana e Bob, cada um com seu Workspace pessoal; as chamadas são feitas como Ana até {@link #actAs}. */
public final class AccountsTestContext {

    public static final Instant NOW = Instant.parse("2026-09-27T12:00:00Z");

    public final InMemoryUserRepository users = new InMemoryUserRepository();
    public final InMemoryWorkspaceRepository workspaces = new InMemoryWorkspaceRepository();
    public final InMemoryAccountRepository accounts = new InMemoryAccountRepository();
    public final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    /** Eventos gravados na outbox (ADR-0018 §2). */
    public final List<IntegrationEvent> outbox = new ArrayList<>();
    public final AccountEvents events = new AccountEvents(event -> {
        outbox.add(event);
        return UUID.randomUUID();
    });

    public final User ana = activeUser("sub-ana");
    public final User bob = activeUser("sub-bob");
    public final Workspace anasWorkspace = personalWorkspace(ana);
    public final Workspace bobsWorkspace = personalWorkspace(bob);

    private User actingUser = ana;

    public final AccountAccess access = new AccountAccess(new CurrentUserService(this::currentIdentity, users),
            new WorkspaceAccessGuard(workspaces), accounts);

    public AccountsTestContext() {
        users.store(ana);
        users.store(bob);
        workspaces.store(anasWorkspace);
        workspaces.store(bobsWorkspace);
    }

    public void actAs(User user) {
        actingUser = user;
    }

    public Account store(Account account) {
        accounts.store(account);
        return account;
    }

    public UUID anasWorkspaceId() {
        return anasWorkspace.id().value();
    }

    private AuthenticatedIdentity currentIdentity() {
        return new AuthenticatedIdentity(actingUser.externalIdentity(), actingUser.email().value(), null, null);
    }
}
