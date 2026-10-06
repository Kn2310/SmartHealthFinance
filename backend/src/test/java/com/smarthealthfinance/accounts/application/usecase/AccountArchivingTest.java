package com.smarthealthfinance.accounts.application.usecase;

import com.smarthealthfinance.accounts.application.AccountsTestContext;
import com.smarthealthfinance.accounts.application.dto.AccountView;
import com.smarthealthfinance.accounts.application.exception.AccountNotFoundException;
import com.smarthealthfinance.accounts.domain.enums.AccountStatus;
import com.smarthealthfinance.accounts.domain.model.Account;
import com.smarthealthfinance.identity.application.exception.WorkspaceNotFoundException;
import org.junit.jupiter.api.Test;

import static com.smarthealthfinance.accounts.AccountsFixtures.account;
import static com.smarthealthfinance.accounts.AccountsFixtures.archivedAccount;
import static com.smarthealthfinance.accounts.application.AccountsTestContext.NOW;
import static com.smarthealthfinance.identity.IdentityFixtures.CREATED_AT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ArchiveAccount + ReactivateAccount. */
class AccountArchivingTest {

    private final AccountsTestContext ctx = new AccountsTestContext();
    private final ArchiveAccount archive = new ArchiveAccount(ctx.access, ctx.accounts, ctx.clock);
    private final ReactivateAccount reactivate = new ReactivateAccount(ctx.access, ctx.accounts, ctx.clock);

    @Test
    void archivesAccount() {
        Account aurora = ctx.store(account(ctx.anasWorkspace.id(), "Banco Aurora"));

        AccountView view = archive.execute(ctx.anasWorkspaceId(), aurora.id().value());

        assertThat(view.status()).isEqualTo(AccountStatus.ARCHIVED);
        assertThat(view.updatedAt()).isEqualTo(NOW);
        assertThat(stored(aurora).status()).isEqualTo(AccountStatus.ARCHIVED);
    }

    @Test
    void archivingTwiceIsIdempotent() {
        Account archived = ctx.store(archivedAccount(ctx.anasWorkspace.id(), "Antiga"));

        AccountView view = archive.execute(ctx.anasWorkspaceId(), archived.id().value());

        assertThat(view.status()).isEqualTo(AccountStatus.ARCHIVED);
        assertThat(view.updatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void reactivatesArchivedAccount() {
        Account archived = ctx.store(archivedAccount(ctx.anasWorkspace.id(), "Antiga"));

        AccountView view = reactivate.execute(ctx.anasWorkspaceId(), archived.id().value());

        assertThat(view.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(stored(archived).status()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void cannotArchiveAccountOfAnotherWorkspace() {
        Account bobs = ctx.store(account(ctx.bobsWorkspace.id(), "Conta do Bob"));

        assertThatThrownBy(() -> archive.execute(ctx.anasWorkspaceId(), bobs.id().value()))
                .isInstanceOf(AccountNotFoundException.class);
        assertThat(stored(bobs).status()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void nonMemberCannotReactivate() {
        Account archived = ctx.store(archivedAccount(ctx.anasWorkspace.id(), "Antiga"));
        ctx.actAs(ctx.bob);

        assertThatThrownBy(() -> reactivate.execute(ctx.anasWorkspaceId(), archived.id().value()))
                .isInstanceOf(WorkspaceNotFoundException.class);
    }

    private Account stored(Account account) {
        return ctx.accounts.findById(account.workspaceId(), account.id()).orElseThrow();
    }
}
