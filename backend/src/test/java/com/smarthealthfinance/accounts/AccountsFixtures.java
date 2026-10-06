package com.smarthealthfinance.accounts;

import com.smarthealthfinance.accounts.domain.enums.AccountType;
import com.smarthealthfinance.accounts.domain.model.Account;
import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.accounts.domain.valueobject.AccountName;
import com.smarthealthfinance.accounts.domain.valueobject.InstitutionName;
import com.smarthealthfinance.identity.domain.model.Workspace;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;

import java.time.Instant;

import static com.smarthealthfinance.identity.IdentityFixtures.CREATED_AT;

public final class AccountsFixtures {

    private AccountsFixtures() {
    }

    public static Account account(WorkspaceId workspaceId, String name) {
        return account(workspaceId, name, CREATED_AT);
    }

    public static Account account(WorkspaceId workspaceId, String name, Instant createdAt) {
        return Account.create(AccountId.generate(createdAt), workspaceId, new AccountName(name), AccountType.CHECKING,
                new InstitutionName("Banco Aurora"), Workspace.DEFAULT_BASE_CURRENCY, true, createdAt);
    }

    public static Account archivedAccount(WorkspaceId workspaceId, String name) {
        Account account = account(workspaceId, name);
        account.archive(CREATED_AT);
        return account;
    }
}
