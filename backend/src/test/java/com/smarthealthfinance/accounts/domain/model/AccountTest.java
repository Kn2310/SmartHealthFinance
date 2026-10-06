package com.smarthealthfinance.accounts.domain.model;

import static com.smarthealthfinance.accounts.AccountsFixtures.account;
import static com.smarthealthfinance.accounts.AccountsFixtures.archivedAccount;
import static com.smarthealthfinance.identity.IdentityFixtures.CREATED_AT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import com.smarthealthfinance.accounts.domain.enums.AccountStatus;
import com.smarthealthfinance.accounts.domain.enums.AccountType;
import com.smarthealthfinance.accounts.domain.exception.AccountArchivedException;
import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.accounts.domain.valueobject.AccountName;
import com.smarthealthfinance.accounts.domain.valueobject.InstitutionName;
import com.smarthealthfinance.identity.domain.model.Workspace;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import org.junit.jupiter.api.Test;

class AccountTest {

    private static final Instant LATER = CREATED_AT.plusSeconds(60);

    private final WorkspaceId workspaceId = WorkspaceId.generate(CREATED_AT);

    @Test
    void createdAccountIsActiveAndUnversioned() {
        Account account = Account.create(AccountId.generate(CREATED_AT), workspaceId, new AccountName("Banco Aurora"),
                AccountType.CHECKING, new InstitutionName("Banco Aurora"), Workspace.DEFAULT_BASE_CURRENCY, true,
                CREATED_AT);

        assertThat(account.workspaceId()).isEqualTo(workspaceId);
        assertThat(account.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.institutionName()).contains(new InstitutionName("Banco Aurora"));
        assertThat(account.currency().getCurrencyCode()).isEqualTo("BRL");
        assertThat(account.includedInTotal()).isTrue();
        assertThat(account.createdAt()).isEqualTo(CREATED_AT);
        assertThat(account.updatedAt()).isEqualTo(CREATED_AT);
        assertThat(account.version()).isZero();
    }

    @Test
    void institutionIsOptional() {
        Account account = Account.create(AccountId.generate(CREATED_AT), workspaceId, new AccountName("Carteira"),
                AccountType.OTHER, null, Workspace.DEFAULT_BASE_CURRENCY, true, CREATED_AT);

        assertThat(account.institutionName()).isEmpty();
    }

    @Test
    void requiresMandatoryFields() {
        assertThatNullPointerException().isThrownBy(() -> Account.create(AccountId.generate(CREATED_AT), null,
                new AccountName("Conta"), AccountType.CHECKING, null, Workspace.DEFAULT_BASE_CURRENCY, true, CREATED_AT));
        assertThatNullPointerException().isThrownBy(() -> Account.create(AccountId.generate(CREATED_AT), workspaceId,
                null, AccountType.CHECKING, null, Workspace.DEFAULT_BASE_CURRENCY, true, CREATED_AT));
        assertThatNullPointerException().isThrownBy(() -> Account.create(AccountId.generate(CREATED_AT), workspaceId,
                new AccountName("Conta"), null, null, Workspace.DEFAULT_BASE_CURRENCY, true, CREATED_AT));
    }

    @Test
    void updateDetailsReplacesEditableFields() {
        Account account = account(workspaceId, "Banco Aurora");

        account.updateDetails(new AccountName("Reserva"), AccountType.SAVINGS, null, false, LATER);

        assertThat(account.name()).isEqualTo(new AccountName("Reserva"));
        assertThat(account.type()).isEqualTo(AccountType.SAVINGS);
        assertThat(account.institutionName()).isEmpty();
        assertThat(account.includedInTotal()).isFalse();
        assertThat(account.updatedAt()).isEqualTo(LATER);
    }

    @Test
    void updateDetailsWithSameValuesKeepsUpdatedAt() {
        Account account = account(workspaceId, "Banco Aurora");

        account.updateDetails(new AccountName("Banco Aurora"), AccountType.CHECKING, new InstitutionName("Banco Aurora"),
                true, LATER);

        assertThat(account.updatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void archivedAccountCannotBeEdited() {
        Account account = archivedAccount(workspaceId, "Banco Aurora");

        assertThatThrownBy(
                () -> account.updateDetails(new AccountName("Outra"), AccountType.OTHER, null, false, LATER))
                .isInstanceOf(AccountArchivedException.class);
        assertThat(account.name()).isEqualTo(new AccountName("Banco Aurora"));
        assertThat(account.updatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void archiveKeepsDataAndIsIdempotent() {
        Account account = account(workspaceId, "Banco Aurora");

        account.archive(LATER);
        account.archive(LATER.plusSeconds(60));

        assertThat(account.status()).isEqualTo(AccountStatus.ARCHIVED);
        assertThat(account.updatedAt()).isEqualTo(LATER);
        assertThat(account.name()).isEqualTo(new AccountName("Banco Aurora"));
    }

    @Test
    void reactivateMakesAccountEditableAgain() {
        Account account = archivedAccount(workspaceId, "Banco Aurora");

        account.reactivate(LATER);

        assertThat(account.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.updatedAt()).isEqualTo(LATER);
        assertThatNoException().isThrownBy(
                () -> account.updateDetails(new AccountName("Outra"), AccountType.OTHER, null, true, LATER));
    }

    @Test
    void reactivatingActiveAccountIsNoOp() {
        Account account = account(workspaceId, "Banco Aurora");

        account.reactivate(LATER);

        assertThat(account.updatedAt()).isEqualTo(CREATED_AT);
    }
}
