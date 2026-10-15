package com.smarthealthfinance.accounts.application.usecase;

import com.smarthealthfinance.accounts.application.dto.AccountView;
import com.smarthealthfinance.accounts.application.service.AccountAccess;
import com.smarthealthfinance.accounts.application.service.AccountEvents;
import com.smarthealthfinance.accounts.domain.model.Account;
import com.smarthealthfinance.accounts.domain.repository.AccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/** Idempotente. Histórico é mantido; não existe exclusão física de conta. */
@Service
public class ArchiveAccount {

    private static final Logger log = LoggerFactory.getLogger(ArchiveAccount.class);

    private final AccountAccess access;
    private final AccountRepository accounts;
    private final Clock clock;
    private final AccountEvents events;

    public ArchiveAccount(AccountAccess access, AccountRepository accounts, Clock clock, AccountEvents events) {
        this.access = access;
        this.accounts = accounts;
        this.clock = clock;
        this.events = events;
    }

    @Transactional
    public AccountView execute(UUID workspaceId, UUID accountId) {
        Account account = access.requireAccount(workspaceId, accountId);

        boolean changed = account.archive(clock.instant());
        accounts.save(account);
        if (changed) {
            events.changed(account);
        }

        log.info("Account archived accountId={} workspaceId={}", account.id(), account.workspaceId());

        return AccountView.from(account);
    }
}
