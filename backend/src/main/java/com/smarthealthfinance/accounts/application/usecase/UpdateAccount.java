package com.smarthealthfinance.accounts.application.usecase;

import com.smarthealthfinance.accounts.application.dto.AccountView;
import com.smarthealthfinance.accounts.application.service.AccountAccess;
import com.smarthealthfinance.accounts.domain.enums.AccountType;
import com.smarthealthfinance.accounts.domain.model.Account;
import com.smarthealthfinance.accounts.domain.repository.AccountRepository;
import com.smarthealthfinance.accounts.domain.valueobject.AccountName;
import com.smarthealthfinance.accounts.domain.valueobject.InstitutionName;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

@Service
public class UpdateAccount {

    private final AccountAccess access;
    private final AccountRepository accounts;
    private final Clock clock;

    public UpdateAccount(AccountAccess access, AccountRepository accounts, Clock clock) {
        this.access = access;
        this.accounts = accounts;
        this.clock = clock;
    }

    /** Substituição completa dos campos editáveis; institutionName em branco remove a instituição. */
    public record Command(String name, String type, String institutionName, boolean includedInTotal) {}

    @Transactional
    public AccountView execute(UUID workspaceId, UUID accountId, Command command) {
        Account account = access.requireAccount(workspaceId, accountId);

        account.updateDetails(new AccountName(command.name()), AccountType.parse(command.type()),
                InstitutionName.ofNullable(command.institutionName()).orElse(null), command.includedInTotal(),
                clock.instant());
        accounts.save(account);

        return AccountView.from(account);
    }
}
