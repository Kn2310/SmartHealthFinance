package com.smarthealthfinance.consents.application;

import com.smarthealthfinance.consents.application.service.ActiveConsents;
import com.smarthealthfinance.consents.application.service.ConsentAccess;
import com.smarthealthfinance.consents.application.service.ConsentEvents;
import com.smarthealthfinance.consents.application.usecase.ChangeConsent;
import com.smarthealthfinance.consents.application.usecase.ListConsents;
import com.smarthealthfinance.identity.application.InMemoryUserRepository;
import com.smarthealthfinance.identity.application.InMemoryWorkspaceRepository;
import com.smarthealthfinance.identity.application.dto.AuthenticatedIdentity;
import com.smarthealthfinance.identity.application.service.CurrentUserService;
import com.smarthealthfinance.identity.application.service.WorkspaceAccessGuard;
import com.smarthealthfinance.identity.domain.model.User;
import com.smarthealthfinance.identity.domain.model.Workspace;
import com.smarthealthfinance.shared.application.event.IntegrationEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.smarthealthfinance.identity.IdentityFixtures.activeUser;
import static com.smarthealthfinance.identity.IdentityFixtures.personalWorkspace;

/** Ana e Bob, cada um com seu Workspace pessoal; as chamadas são feitas como Ana até {@link #actAs}. */
public final class ConsentsTestContext {

    public static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");

    public final InMemoryUserRepository users = new InMemoryUserRepository();
    public final InMemoryWorkspaceRepository workspaces = new InMemoryWorkspaceRepository();
    public final InMemoryConsentRepository consents = new InMemoryConsentRepository();
    public Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    public final List<IntegrationEvent> outbox = new ArrayList<>();
    public final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    public final User ana = activeUser("sub-ana");
    public final User bob = activeUser("sub-bob");
    public final Workspace anasWorkspace = personalWorkspace(ana);
    public final Workspace bobsWorkspace = personalWorkspace(bob);

    private User actingUser = ana;

    public final ConsentAccess access = new ConsentAccess(new CurrentUserService(this::currentIdentity, users),
            new WorkspaceAccessGuard(workspaces));
    public final ConsentEvents events = new ConsentEvents(event -> {
        outbox.add(event);
        return UUID.randomUUID();
    });
    public final ListConsents list = new ListConsents(access, consents);
    public final ActiveConsents reader = new ActiveConsents(consents);

    public ConsentsTestContext() {
        users.store(ana);
        users.store(bob);
        workspaces.store(anasWorkspace);
        workspaces.store(bobsWorkspace);
    }

    /** Usa o relógio corrente: avance {@link #clock} antes de chamar. */
    public ChangeConsent change() {
        return new ChangeConsent(access, consents, consents, events, clock, meters);
    }

    public void actAs(User user) {
        actingUser = user;
    }

    public UUID anasWorkspaceId() {
        return anasWorkspace.id().value();
    }

    private AuthenticatedIdentity currentIdentity() {
        return new AuthenticatedIdentity(actingUser.externalIdentity(), actingUser.email().value(), null, null);
    }
}
