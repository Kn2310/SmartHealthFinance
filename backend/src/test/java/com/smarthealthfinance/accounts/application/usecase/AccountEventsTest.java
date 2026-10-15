package com.smarthealthfinance.accounts.application.usecase;

import com.smarthealthfinance.accounts.application.AccountsTestContext;
import com.smarthealthfinance.accounts.application.service.AccountEvents;
import com.smarthealthfinance.accounts.domain.model.Account;
import com.smarthealthfinance.shared.application.event.IntegrationEvent;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static com.smarthealthfinance.accounts.AccountsFixtures.account;
import static org.assertj.core.api.Assertions.assertThat;

/** {@code account.changed} v1 (ADR-0018 §2): só quando a conta muda, e só com o id no payload. */
class AccountEventsTest {

    private final AccountsTestContext ctx = new AccountsTestContext();
    private final CreateAccount create = new CreateAccount(ctx.access, ctx.accounts, ctx.clock, ctx.events);
    private final UpdateAccount update = new UpdateAccount(ctx.access, ctx.accounts, ctx.clock, ctx.events);
    private final ArchiveAccount archive = new ArchiveAccount(ctx.access, ctx.accounts, ctx.clock, ctx.events);
    private final ReactivateAccount reactivate = new ReactivateAccount(ctx.access, ctx.accounts, ctx.clock,
            ctx.events);

    @Test
    void creatingAnAccountEmitsTheEventWithOnlyItsId() {
        UUID id = create.execute(ctx.anasWorkspaceId(),
                new CreateAccount.Command("Reserva", "SAVINGS", "Banco Norte", false)).id();

        assertThat(ctx.outbox).singleElement().satisfies(event -> {
            assertThat(event.eventType()).isEqualTo(AccountEvents.CHANGED);
            assertThat(event.eventVersion()).isEqualTo(1);
            assertThat(event.workspaceId()).isEqualTo(ctx.anasWorkspaceId());
            assertThat(event.aggregateType()).isEqualTo("Account");
            assertThat(event.aggregateId()).isEqualTo(id);
            assertThat(event.payload()).isEqualTo(Map.of("accountId", id.toString()));
        });
    }

    @Test
    void editingWithoutChangesEmitsNothing() {
        Account aurora = ctx.store(account(ctx.anasWorkspace.id(), "Banco Aurora"));

        update.execute(ctx.anasWorkspaceId(), aurora.id().value(),
                new UpdateAccount.Command("Banco Aurora", "CHECKING", "Banco Aurora", true));
        assertThat(ctx.outbox).isEmpty();

        update.execute(ctx.anasWorkspaceId(), aurora.id().value(),
                new UpdateAccount.Command("Banco Aurora", "SAVINGS", "Banco Aurora", true));
        assertThat(ctx.outbox).extracting(IntegrationEvent::eventType).containsExactly(AccountEvents.CHANGED);
    }

    @Test
    void archivingAndReactivatingEmitOnlyOnTheTransition() {
        Account aurora = ctx.store(account(ctx.anasWorkspace.id(), "Banco Aurora"));

        reactivate.execute(ctx.anasWorkspaceId(), aurora.id().value());
        archive.execute(ctx.anasWorkspaceId(), aurora.id().value());
        archive.execute(ctx.anasWorkspaceId(), aurora.id().value());
        reactivate.execute(ctx.anasWorkspaceId(), aurora.id().value());

        assertThat(ctx.outbox).hasSize(2);
    }
}
