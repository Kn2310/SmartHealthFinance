package com.smarthealthfinance.accounts.application.usecase;

import com.smarthealthfinance.accounts.application.AccountsTestContext;
import com.smarthealthfinance.accounts.application.dto.AccountView;
import com.smarthealthfinance.accounts.domain.enums.AccountStatus;
import com.smarthealthfinance.accounts.domain.enums.AccountType;
import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.identity.application.exception.UserNotProvisionedException;
import com.smarthealthfinance.identity.application.exception.WorkspaceNotFoundException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static com.smarthealthfinance.accounts.application.AccountsTestContext.NOW;
import static com.smarthealthfinance.identity.IdentityFixtures.activeUser;
import static com.smarthealthfinance.shared.domain.InvalidValueAssert.assertInvalidValue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreateAccountTest {

    private final AccountsTestContext ctx = new AccountsTestContext();
    private final CreateAccount create = new CreateAccount(ctx.access, ctx.accounts, ctx.clock);

    @Test
    void createsActiveAccountInTheWorkspace() {
        AccountView view = create.execute(ctx.anasWorkspaceId(),
                new CreateAccount.Command("  Banco Aurora ", "CHECKING", " Banco Aurora ", null));

        assertThat(view.id().version()).isEqualTo(7);
        assertThat(view.workspaceId()).isEqualTo(ctx.anasWorkspaceId());
        assertThat(view.name()).isEqualTo("Banco Aurora");
        assertThat(view.type()).isEqualTo(AccountType.CHECKING);
        assertThat(view.institutionName()).isEqualTo("Banco Aurora");
        assertThat(view.currency()).isEqualTo("BRL");
        assertThat(view.includedInTotal()).isTrue();
        assertThat(view.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(view.createdAt()).isEqualTo(NOW);
        assertThat(ctx.accounts.findById(ctx.anasWorkspace.id(), new AccountId(view.id()))).isPresent();
    }

    @Test
    void institutionIsOptionalAndIncludedInTotalCanBeDisabled() {
        AccountView view = create.execute(ctx.anasWorkspaceId(),
                new CreateAccount.Command("Carteira", "OTHER", "   ", false));

        assertThat(view.institutionName()).isNull();
        assertThat(view.includedInTotal()).isFalse();
    }

    @Test
    void rejectsInvalidNameWithoutPersisting() {
        assertInvalidValue(() -> create.execute(ctx.anasWorkspaceId(),
                new CreateAccount.Command("", "CHECKING", null, null)), "name", "REQUIRED");
        assertThat(ctx.accounts.size()).isZero();
    }

    @Test
    void rejectsUnknownTypeWithoutPersisting() {
        assertInvalidValue(() -> create.execute(ctx.anasWorkspaceId(),
                new CreateAccount.Command("Cartão", "CREDIT_CARD", null, null)), "type", "INVALID");
        assertThat(ctx.accounts.size()).isZero();
    }

    @Test
    void nonMemberCannotCreateInAnotherWorkspace() {
        ctx.actAs(ctx.bob);

        assertThatThrownBy(() -> create.execute(ctx.anasWorkspaceId(),
                new CreateAccount.Command("Invasora", "CHECKING", null, null)))
                .isInstanceOf(WorkspaceNotFoundException.class);
        assertThat(ctx.accounts.size()).isZero();
    }

    /** Autorização antes da validação: dados inválidos não revelam nada sobre Workspaces alheios. */
    @Test
    void authorizationRunsBeforeValidation() {
        ctx.actAs(ctx.bob);

        assertThatThrownBy(() -> create.execute(ctx.anasWorkspaceId(), new CreateAccount.Command("", "???", null, null)))
                .isInstanceOf(WorkspaceNotFoundException.class);
    }

    @Test
    void unknownWorkspaceIsNotFound() {
        assertThatThrownBy(() -> create.execute(UUID.randomUUID(),
                new CreateAccount.Command("Conta", "CHECKING", null, null)))
                .isInstanceOf(WorkspaceNotFoundException.class);
    }

    @Test
    void requiresProvisionedUser() {
        ctx.actAs(activeUser("sub-ghost"));

        assertThatThrownBy(() -> create.execute(ctx.anasWorkspaceId(),
                new CreateAccount.Command("Conta", "CHECKING", null, null)))
                .isInstanceOf(UserNotProvisionedException.class);
    }
}
