package com.smarthealthfinance.accounts.application.usecase;

import com.smarthealthfinance.accounts.application.AccountsTestContext;
import com.smarthealthfinance.accounts.application.dto.AccountView;
import com.smarthealthfinance.accounts.application.exception.AccountNotFoundException;
import com.smarthealthfinance.accounts.domain.enums.AccountType;
import com.smarthealthfinance.accounts.domain.exception.AccountArchivedException;
import com.smarthealthfinance.accounts.domain.model.Account;
import com.smarthealthfinance.accounts.domain.valueobject.AccountName;
import com.smarthealthfinance.identity.application.exception.WorkspaceNotFoundException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static com.smarthealthfinance.accounts.AccountsFixtures.account;
import static com.smarthealthfinance.accounts.AccountsFixtures.archivedAccount;
import static com.smarthealthfinance.accounts.application.AccountsTestContext.NOW;
import static com.smarthealthfinance.shared.domain.InvalidValueAssert.assertInvalidValue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UpdateAccountTest {

    private final AccountsTestContext ctx = new AccountsTestContext();
    private final UpdateAccount update = new UpdateAccount(ctx.access, ctx.accounts, ctx.clock);
    private final Account aurora = ctx.store(account(ctx.anasWorkspace.id(), "Banco Aurora"));

    @Test
    void replacesEditableFields() {
        AccountView view = update.execute(ctx.anasWorkspaceId(), aurora.id().value(),
                new UpdateAccount.Command(" Reserva ", "SAVINGS", "Banco Norte", false));

        assertThat(view.name()).isEqualTo("Reserva");
        assertThat(view.type()).isEqualTo(AccountType.SAVINGS);
        assertThat(view.institutionName()).isEqualTo("Banco Norte");
        assertThat(view.includedInTotal()).isFalse();
        assertThat(view.updatedAt()).isEqualTo(NOW);
        assertThat(ctx.accounts.saveCount()).isEqualTo(1);
        assertThat(stored(aurora).version()).isEqualTo(1);
    }

    @Test
    void blankInstitutionClearsIt() {
        AccountView view = update.execute(ctx.anasWorkspaceId(), aurora.id().value(),
                new UpdateAccount.Command("Banco Aurora", "CHECKING", "  ", true));

        assertThat(view.institutionName()).isNull();
    }

    @Test
    void archivedAccountIsNotUpdated() {
        Account archived = ctx.store(archivedAccount(ctx.anasWorkspace.id(), "Antiga"));

        assertThatThrownBy(() -> update.execute(ctx.anasWorkspaceId(), archived.id().value(),
                new UpdateAccount.Command("Nova", "OTHER", null, true)))
                .isInstanceOf(AccountArchivedException.class);
        assertThat(ctx.accounts.saveCount()).isZero();
        assertThat(stored(archived).name()).isEqualTo(new AccountName("Antiga"));
    }

    @Test
    void rejectsInvalidValuesWithoutSaving() {
        assertInvalidValue(() -> update.execute(ctx.anasWorkspaceId(), aurora.id().value(),
                new UpdateAccount.Command("Banco Aurora", "INVESTMENT", null, true)), "type", "INVALID");
        assertThat(ctx.accounts.saveCount()).isZero();
    }

    @Test
    void accountOfAnotherWorkspaceIsNotFoundThroughOwnWorkspace() {
        Account bobs = ctx.store(account(ctx.bobsWorkspace.id(), "Conta do Bob"));

        assertThatThrownBy(() -> update.execute(ctx.anasWorkspaceId(), bobs.id().value(),
                new UpdateAccount.Command("Sequestrada", "OTHER", null, true)))
                .isInstanceOf(AccountNotFoundException.class);
        assertThat(stored(bobs).name()).isEqualTo(new AccountName("Conta do Bob"));
    }

    @Test
    void nonMemberGetsWorkspaceNotFound() {
        ctx.actAs(ctx.bob);

        assertThatThrownBy(() -> update.execute(ctx.anasWorkspaceId(), aurora.id().value(),
                new UpdateAccount.Command("Sequestrada", "OTHER", null, true)))
                .isInstanceOf(WorkspaceNotFoundException.class);
        assertThat(ctx.accounts.saveCount()).isZero();
    }

    @Test
    void unknownAccountIsNotFound() {
        assertThatThrownBy(() -> update.execute(ctx.anasWorkspaceId(), UUID.randomUUID(),
                new UpdateAccount.Command("Conta", "OTHER", null, true)))
                .isInstanceOf(AccountNotFoundException.class);
    }

    private Account stored(Account account) {
        return ctx.accounts.findById(account.workspaceId(), account.id()).orElseThrow();
    }
}
