package com.smarthealthfinance.accounts.application.usecase;

import com.smarthealthfinance.accounts.application.dto.AccountView;
import com.smarthealthfinance.accounts.application.service.AccountAccess;
import com.smarthealthfinance.accounts.domain.model.Account;
import com.smarthealthfinance.accounts.domain.repository.AccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/** Idempotente. */
@Service
public class ReactivateAccount {

    private static final Logger log = LoggerFactory.getLogger(ReactivateAccount.class);

    private final AccountAccess access;
    private final AccountRepository accounts;
    private final Clock clock;

    public ReactivateAccount(AccountAccess access, AccountRepository accounts, Clock clock) {
        this.access = access;
        this.accounts = accounts;
        this.clock = clock;
    }

    @Transactional
    public AccountView execute(UUID workspaceId, UUID accountId) {
        Account account = access.requireAccount(workspaceId, accountId);

        account.reactivate(clock.instant());
        accounts.save(account);

        log.info("Account reactivated accountId={} workspaceId={}", account.id(), account.workspaceId());

        return AccountView.from(account);
    }
}
