package com.smarthealthfinance.accounts.application.usecase;

import com.smarthealthfinance.accounts.application.AccountsTestContext;
import com.smarthealthfinance.accounts.application.dto.AccountView;
import com.smarthealthfinance.accounts.application.exception.AccountNotFoundException;
import com.smarthealthfinance.accounts.domain.model.Account;
import com.smarthealthfinance.identity.application.exception.WorkspaceNotFoundException;
import org.junit.jupiter.api.Test;

import static com.smarthealthfinance.accounts.AccountsFixtures.account;
import static com.smarthealthfinance.accounts.AccountsFixtures.archivedAccount;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ListAccounts + GetAccount. */
class AccountQueriesTest {

    private final AccountsTestContext ctx = new AccountsTestContext();
    private final ListAccounts list = new ListAccounts(ctx.access, ctx.accounts);
    private final GetAccount get = new GetAccount(ctx.access);

    @Test
    void listsOnlyActiveAccountsByDefault() {
        ctx.store(account(ctx.anasWorkspace.id(), "Banco Aurora"));
        ctx.store(account(ctx.anasWorkspace.id(), "Banco Norte"));
        ctx.store(archivedAccount(ctx.anasWorkspace.id(), "Antiga"));

        assertThat(list.execute(ctx.anasWorkspaceId(), false)).extracting(AccountView::name)
                .containsExactly("Banco Aurora", "Banco Norte");
        assertThat(list.execute(ctx.anasWorkspaceId(), true)).extracting(AccountView::name)
                .containsExactly("Banco Aurora", "Banco Norte", "Antiga");
    }

    @Test
    void listNeverMixesWorkspaces() {
        ctx.store(account(ctx.anasWorkspace.id(), "Banco Aurora"));
        ctx.store(account(ctx.bobsWorkspace.id(), "Conta do Bob"));

        assertThat(list.execute(ctx.anasWorkspaceId(), true)).extracting(AccountView::name)
                .containsExactly("Banco Aurora");
    }

    @Test
    void emptyWorkspaceHasNoAccounts() {
        assertThat(list.execute(ctx.anasWorkspaceId(), true)).isEmpty();
    }

    @Test
    void nonMemberCannotList() {
        ctx.actAs(ctx.bob);

        assertThatThrownBy(() -> list.execute(ctx.anasWorkspaceId(), false))
                .isInstanceOf(WorkspaceNotFoundException.class);
    }

    @Test
    void getsAccountById() {
        Account aurora = ctx.store(account(ctx.anasWorkspace.id(), "Banco Aurora"));

        assertThat(get.execute(ctx.anasWorkspaceId(), aurora.id().value()).name()).isEqualTo("Banco Aurora");
    }

    @Test
    void getAccountOfAnotherWorkspaceIsNotFound() {
        Account bobs = ctx.store(account(ctx.bobsWorkspace.id(), "Conta do Bob"));

        assertThatThrownBy(() -> get.execute(ctx.anasWorkspaceId(), bobs.id().value()))
                .isInstanceOf(AccountNotFoundException.class);
    }
}
