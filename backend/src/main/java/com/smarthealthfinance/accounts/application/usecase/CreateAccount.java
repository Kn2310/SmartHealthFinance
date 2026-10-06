package com.smarthealthfinance.accounts.application.usecase;

import com.smarthealthfinance.accounts.application.dto.AccountView;
import com.smarthealthfinance.accounts.application.service.AccountAccess;
import com.smarthealthfinance.accounts.domain.enums.AccountType;
import com.smarthealthfinance.accounts.domain.model.Account;
import com.smarthealthfinance.accounts.domain.repository.AccountRepository;
import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.accounts.domain.valueobject.AccountName;
import com.smarthealthfinance.accounts.domain.valueobject.InstitutionName;
import com.smarthealthfinance.identity.domain.model.Workspace;
import com.smarthealthfinance.identity.domain.valueobject.WorkspaceId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
public class CreateAccount {

    private static final Logger log = LoggerFactory.getLogger(CreateAccount.class);

    private final AccountAccess access;
    private final AccountRepository accounts;
    private final Clock clock;

    public CreateAccount(AccountAccess access, AccountRepository accounts, Clock clock) {
        this.access = access;
        this.accounts = accounts;
        this.clock = clock;
    }

    /** {@code includedInTotal} nulo assume true (padrão do design). */
    public record Command(String name, String type, String institutionName, Boolean includedInTotal) {}

    @Transactional
    public AccountView execute(UUID workspaceId, Command command) {
        WorkspaceId workspace = access.requireWorkspace(workspaceId);
        Instant now = clock.instant();

        // MVP: moeda da conta = moeda base fixa do Workspace (ADR-0003).
        Account account = Account.create(AccountId.generate(now), workspace, new AccountName(command.name()),
                AccountType.parse(command.type()), InstitutionName.ofNullable(command.institutionName()).orElse(null),
                Workspace.DEFAULT_BASE_CURRENCY, command.includedInTotal() == null || command.includedInTotal(), now);

        accounts.add(account);

        log.info("Account created accountId={} workspaceId={}", account.id(), workspace);

        return AccountView.from(account);
    }
}
